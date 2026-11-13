package com.digihealth.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.caregiver.ProviderPayout;
import com.digihealth.caregiver.ProviderPayoutRepository;
import com.digihealth.carerequest.CareRequest;
import com.digihealth.carerequest.CareRequestRepository;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.notification.NotificationService;
import com.digihealth.provider.Provider;
import com.digihealth.provider.ProviderRepository;
import com.digihealth.security.FieldCrypto;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/*
 * Monthly payroll: salaried office staff (staff_salaries) plus providers paid by their
 * earning structure (a proposed rate is approved when Finance processes the payroll). Until the month is processed the lines are a live draft;
 * processing saves them (and a payout each provider sees), then Finance marks each one paid.
 */
@Service
public class PayrollService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final PayrollLineRepository lines;
    private final StaffSalaryRepository salaries;
    private final ProviderRepository providers;
    private final ProviderPayoutRepository payouts;
    private final CareRequestRepository careRequests;
    private final UserRepository users;
    private final FieldCrypto crypto;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final SecureRandom random = new SecureRandom();

    public PayrollService(PayrollLineRepository lines, StaffSalaryRepository salaries, ProviderRepository providers,
                          ProviderPayoutRepository payouts, CareRequestRepository careRequests, UserRepository users,
                          FieldCrypto crypto, NotificationService notifications, ActivityService activity) {
        this.lines = lines;
        this.salaries = salaries;
        this.providers = providers;
        this.payouts = payouts;
        this.careRequests = careRequests;
        this.users = users;
        this.crypto = crypto;
        this.notifications = notifications;
        this.activity = activity;
    }

    public record LineView(Long id, String staffName, String fullName, Long userId, String role, String payBasis,
                           Integer requestsCompleted, BigDecimal grossPay, BigDecimal allowances, BigDecimal deductions,
                           BigDecimal netPay, String bankName, String accountName, String accountNumber, String status,
                           String reference, Instant paidAt) {
    }

    public record PayrollView(String month, String status, int staffCount, BigDecimal totalNet, List<LineView> lines) {
    }

    @Transactional(readOnly = true)
    public PayrollView payroll(String monthText) {
        YearMonth month = Ledger.month(monthText);
        List<PayrollLine> saved = lines.findByMonthOrderByStaffNameAsc(month.toString());
        List<PayrollLine> list = saved.isEmpty() ? draft(month) : saved;
        String status = saved.isEmpty() ? "DRAFT"
            : saved.stream().allMatch(l -> "PAID".equals(l.getStatus())) ? "PAID" : "PROCESSED";
        List<LineView> views = list.stream().map(l -> view(l, saved.isEmpty())).toList();
        BigDecimal total = list.stream().map(PayrollLine::getNetPay).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PayrollView(month.toString(), status, views.size(), total, views);
    }

    @Transactional
    public PayrollView process(String monthText) {
        YearMonth month = Ledger.month(monthText);
        if (month.isAfter(YearMonth.from(AppTime.today()))) {
            throw ApiException.badRequest("You can't process a future month.");
        }
        if (lines.existsByMonth(month.toString())) {
            throw ApiException.conflict(month + " payroll has already been processed.");
        }
        List<PayrollLine> draft = draft(month);
        if (draft.isEmpty()) {
            throw ApiException.badRequest("There's nobody to pay for " + month
                + ". Providers need an approved earning structure; salaried staff need a salary set up.");
        }
        Long me = CurrentUser.id();
        BigDecimal total = BigDecimal.ZERO;
        for (PayrollLine l : draft) {
            l.setProcessedById(me);
            if (l.getProviderId() != null) {
                providers.findById(l.getProviderId()).ifPresent(pr -> {
                    if (!"APPROVED".equals(pr.getEarningStatus())) {
                        pr.setEarningStatus("APPROVED");
                        pr.setEarningUpdatedAt(Instant.now());
                        if (pr.getUserId() != null) {
                            notifications.notify(pr.getUserId(), "Earning structure approved",
                                "Finance approved your " + pr.getEarningStructure().toLowerCase().replace('_', ' ')
                                    + " rate of " + BillingService.naira(pr.getEarningRate()) + ".", "/caregiver/earnings");
                        }
                    }
                });
                ProviderPayout p = new ProviderPayout(l.getProviderId(), month.atDay(1), month.atEndOfMonth(), l.getNetPay());
                p.setCompletedVisits(l.getRequestsCompleted() == null ? 0 : l.getRequestsCompleted());
                p.setStructure(l.getPayBasis());
                l.setPayoutId(payouts.save(p).getId());
            }
            lines.save(l);
            total = total.add(l.getNetPay());
            if (l.getUserId() != null) {
                notifications.notify(l.getUserId(), "Payslip ready", "Your pay for " + label(month) + " is "
                    + BillingService.naira(l.getNetPay()) + ". You'll be told once it's paid.", null);
            }
        }
        activity.record("PAYROLL_PROCESSED", "Processed " + label(month) + " payroll: " + BillingService.naira(total)
            + " for " + draft.size() + " staff", "PAYROLL", null);
        return payroll(month.toString());
    }

    @Transactional
    public LineView markPaid(Long id, String reference) {
        PayrollLine l = lines.findById(id).orElseThrow(() -> ApiException.notFound("Payslip not found."));
        if ("PAID".equals(l.getStatus())) {
            throw ApiException.conflict("This payslip is already paid.");
        }
        String ref = reference == null || reference.isBlank() ? "PAY-" + code() : reference.trim();
        if (ref.length() > 100) {
            throw ApiException.badRequest("Reference is too long.");
        }
        Instant now = Instant.now();
        l.setStatus("PAID");
        l.setReference(ref);
        l.setPaidAt(now);
        if (l.getPayoutId() != null) {
            payouts.findById(l.getPayoutId()).ifPresent(p -> {
                p.setStatus("PAID");
                p.setReference(ref);
                p.setPaidAt(now);
            });
        }
        if (l.getUserId() != null) {
            notifications.notify(l.getUserId(), "Salary paid", BillingService.naira(l.getNetPay()) + " for "
                + label(YearMonth.parse(l.getMonth())) + " has been paid (ref " + ref + ").", null);
        }
        activity.record("PAYSLIP_PAID", "Paid " + l.getStaffName() + " " + BillingService.naira(l.getNetPay())
            + " for " + l.getMonth(), "PAYROLL_LINE", l.getId());
        return view(l, false);
    }

    /** Total net pay for a month (processed lines, or the live draft). */
    @Transactional(readOnly = true)
    public PayrollView totals(String month) {
        return payroll(month);
    }

    /* ── the live draft ── */

    private List<PayrollLine> draft(YearMonth month) {
        String key = month.toString();
        List<PayrollLine> out = new ArrayList<>();

        List<StaffSalary> staff = salaries.findByActiveTrue();
        Map<Long, User> userById = users.findAllById(staff.stream().map(StaffSalary::getUserId).toList()).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        for (StaffSalary s : staff) {
            User u = userById.get(s.getUserId());
            if (u == null || !u.isActive()) {
                continue;
            }
            PayrollLine l = new PayrollLine(key, u.getFullName(), "SALARY");
            l.setUserId(u.getId());
            l.setRole(u.getRole().name());
            l.setGrossPay(s.getMonthlySalary());
            l.setAllowances(s.getAllowances());
            l.setDeductions(s.getDeductions());
            l.setNetPay(s.getMonthlySalary().add(s.getAllowances()).subtract(s.getDeductions()).max(BigDecimal.ZERO));
            l.setBankName(s.getBankName());
            l.setAccountName(s.getAccountName());
            l.setAccountNumberEnc(s.getAccountNumberEnc());
            out.add(l);
        }

        Instant from = AppTime.startOf(month.atDay(1));
        Instant to = AppTime.startOf(month.plusMonths(1).atDay(1));
        for (Provider p : providers.findAllByOrderByCreatedAtDesc()) {
            /* a proposed (PENDING) rate shows on the draft; processing the payroll approves it */
            if (!"APPROVED".equals(p.getStatus()) || "REJECTED".equals(p.getEarningStatus())
                || p.getEarningRate() == null || p.getEarningStructure() == null) {
                continue;
            }
            int done = (int) careRequests.findByAssignedProviderIdOrderByCreatedAtDesc(p.getId()).stream()
                .filter(r -> CareRequest.COMPLETED.equals(r.getStatus()) && r.getClosedAt() != null
                    && !r.getClosedAt().isBefore(from) && r.getClosedAt().isBefore(to))
                .count();
            BigDecimal gross = switch (p.getEarningStructure()) {
                case "PER_REQUEST" -> p.getEarningRate().multiply(BigDecimal.valueOf(done));
                /* 52 weeks over 12 months */
                case "WEEKLY" -> p.getEarningRate().multiply(BigDecimal.valueOf(52))
                    .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
                case "MONTHLY" -> p.getEarningRate();
                default -> BigDecimal.ZERO;
            };
            if (gross.signum() <= 0) {
                continue;
            }
            PayrollLine l = new PayrollLine(key, p.getFullName(), p.getEarningStructure());
            l.setUserId(p.getUserId());
            l.setProviderId(p.getId());
            l.setRole(p.getProviderType() != null ? p.getProviderType() : "SERVICE_PROVIDER");
            l.setRequestsCompleted(done);
            l.setGrossPay(gross.setScale(2, RoundingMode.HALF_UP));
            l.setNetPay(gross.setScale(2, RoundingMode.HALF_UP));
            l.setBankName(p.getBankName());
            l.setAccountName(p.getAccountName());
            l.setAccountNumberEnc(p.getAccountNumberEnc());
            out.add(l);
        }
        out.sort((a, b) -> a.getStaffName().compareToIgnoreCase(b.getStaffName()));
        return out;
    }

    private LineView view(PayrollLine l, boolean draft) {
        return new LineView(draft ? null : l.getId(), l.getStaffName(), l.getStaffName(), l.getUserId(), l.getRole(),
            l.getPayBasis(), l.getRequestsCompleted(), l.getGrossPay(), l.getAllowances(), l.getDeductions(),
            l.getNetPay(), l.getBankName(), l.getAccountName(), decrypt(l.getAccountNumberEnc()),
            draft ? "DRAFT" : l.getStatus(), l.getReference(), l.getPaidAt());
    }

    /** Finance needs the full number to pay; it's only ever shown on this page. */
    private String decrypt(String enc) {
        if (enc == null) {
            return null;
        }
        try {
            return crypto.decrypt(enc);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static String label(YearMonth m) {
        return m.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + m.getYear();
    }

    private String code() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
