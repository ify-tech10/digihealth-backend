package com.digihealth.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.activity.ActivityService.ActivityView;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.facility.Facility;
import com.digihealth.facility.FacilityInvoice;
import com.digihealth.facility.FacilityInvoiceItem;
import com.digihealth.facility.FacilityInvoiceItemRepository;
import com.digihealth.facility.FacilityInvoiceRepository;
import com.digihealth.facility.FacilityRepository;
import com.digihealth.file.FileService;
import com.digihealth.hmo.HmoOrganisation;
import com.digihealth.hmo.HmoOrganisationRepository;
import com.digihealth.notification.NotificationService;
import com.digihealth.payment.Payment;
import com.digihealth.payment.PaymentRepository;
import com.digihealth.security.FieldCrypto;
import com.digihealth.user.Role;

/*
 * The Finance Manager's portal apart from invoices/payments (BillingService) and payroll
 * (PayrollService): dashboard numbers, HMO claims, expense claims, facility bills, budget, reports.
 */
@Service
public class FinanceService {

    /** The budget page's starting categories (frontend Budget.jsx). */
    static final List<String> DEFAULT_BUDGET = List.of("Staff salaries", "Provider payouts", "Medical supplies",
        "Lab & diagnostics", "Facility bills", "Transport & logistics", "IT & telecom", "Training", "Marketing",
        "Admin & office");
    private static final Set<String> CLAIM_OPEN = Set.of("PENDING", "QUERIED");
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Ledger ledger;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final HmoClaimRepository claims;
    private final FinancialRequestRepository requests;
    private final FacilityInvoiceRepository facilityInvoices;
    private final FacilityInvoiceItemRepository facilityItems;
    private final FacilityRepository facilities;
    private final HmoOrganisationRepository organisations;
    private final BudgetLineRepository budget;
    private final PayrollService payroll;
    private final PayrollLineRepository payrollLines;
    private final FileService files;
    private final FieldCrypto crypto;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final SecureRandom random = new SecureRandom();

    public FinanceService(Ledger ledger, InvoiceRepository invoices, PaymentRepository payments, HmoClaimRepository claims,
                          FinancialRequestRepository requests, FacilityInvoiceRepository facilityInvoices,
                          FacilityInvoiceItemRepository facilityItems, FacilityRepository facilities,
                          HmoOrganisationRepository organisations, BudgetLineRepository budget, PayrollService payroll,
                          PayrollLineRepository payrollLines, FileService files, FieldCrypto crypto,
                          NotificationService notifications, ActivityService activity) {
        this.ledger = ledger;
        this.invoices = invoices;
        this.payments = payments;
        this.claims = claims;
        this.requests = requests;
        this.facilityInvoices = facilityInvoices;
        this.facilityItems = facilityItems;
        this.facilities = facilities;
        this.organisations = organisations;
        this.budget = budget;
        this.payroll = payroll;
        this.payrollLines = payrollLines;
        this.files = files;
        this.crypto = crypto;
        this.notifications = notifications;
        this.activity = activity;
    }

    /* ══ Dashboard ══ */

    @Transactional(readOnly = true)
    public Map<String, Object> summary(String monthText) {
        YearMonth month = Ledger.month(monthText);
        LocalDate from = month.atDay(1);
        LocalDate to = month.plusMonths(1).atDay(1);
        BigDecimal revenue = Ledger.total(ledger.income(from, to));
        BigDecimal expenses = Ledger.total(ledger.spending(from, to));

        List<Invoice> open = invoices.findAllByOrderByCreatedAtDesc().stream()
            .filter(i -> Invoice.PENDING.equals(i.getStatus()) || Invoice.PARTIAL.equals(i.getStatus())).toList();
        List<HmoClaim> openClaims = claims.findAll().stream().filter(c -> CLAIM_OPEN.contains(c.getStatus())).toList();
        List<FinancialRequest> waiting = expenseSource().stream().filter(r -> r.getFinanceDecision() == null).toList();
        PayrollService.PayrollView pay = payroll.payroll(month.toString());
        BigDecimal received = payments.findByStatusOrderByPaidAtDesc(Payment.PAID).stream()
            .filter(p -> Ledger.in(Ledger.day(p.getPaidAt() != null ? p.getPaidAt() : p.getCreatedAt()), from, to))
            .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budgeted = budget.findByMonthOrderByIdAsc(month.toString()).stream()
            .map(BudgetLine::getBudgeted).reduce(BigDecimal.ZERO, BigDecimal::add);

        /* invoices due this month: share paid */
        List<Invoice> due = invoices.findAllByOrderByCreatedAtDesc().stream()
            .filter(i -> !Invoice.CANCELLED.equals(i.getStatus()) && Ledger.in(i.getDueDate(), from, to)).toList();
        long paidDue = due.stream().filter(i -> Invoice.PAID.equals(i.getStatus())).count();
        List<HmoClaim> settled = claims.findAll().stream()
            .filter(c -> "PAID".equals(c.getStatus()) && Ledger.in(c.getPaidAt(), from, to)).toList();
        BigDecimal claimed = settled.stream().map(HmoClaim::getAmountClaimed).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recovered = settled.stream().map(c -> c.getAmountReceived() == null ? BigDecimal.ZERO : c.getAmountReceived())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("month", month.toString());
        m.put("revenue", revenue);
        m.put("expenses", expenses);
        m.put("netProfit", revenue.subtract(expenses));
        m.put("outstanding", open.stream().map(Invoice::balance).reduce(BigDecimal.ZERO, BigDecimal::add));
        m.put("outstandingCount", open.size());
        m.put("overdueCount", open.stream().filter(i -> BillingService.isOverdue(i, AppTime.today())).count());
        m.put("hmoPending", openClaims.stream().map(HmoClaim::getAmountClaimed).reduce(BigDecimal.ZERO, BigDecimal::add));
        m.put("hmoPendingCount", openClaims.size());
        m.put("expenseClaimsPending", waiting.stream().map(FinancialRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        m.put("expenseClaimsCount", waiting.size());
        m.put("payroll", pay.totalNet());
        m.put("payrollStaff", pay.staffCount());
        m.put("payrollStatus", pay.status());
        m.put("paymentsReceived", received);
        m.put("budgetTotal", budgeted);
        m.put("budgetUsed", budgeted.signum() > 0 ? pct(expenses, budgeted) : null);
        m.put("collectionRate", due.isEmpty() ? null : pct(BigDecimal.valueOf(paidDue), BigDecimal.valueOf(due.size())));
        m.put("expenseRatio", revenue.signum() > 0 ? pct(expenses, revenue) : null);
        m.put("hmoRecoveryRate", claimed.signum() > 0 ? pct(recovered, claimed) : null);
        m.put("netMargin", revenue.signum() > 0 ? pct(revenue.subtract(expenses), revenue) : null);
        return m;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> monthly(Integer yearOrNull) {
        int year = yearOrNull == null ? AppTime.today().getYear() : yearOrNull;
        if (year < 2000 || year > 2100) {
            throw ApiException.badRequest("Invalid year.");
        }
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year + 1, 1, 1);
        Map<String, BigDecimal> in = byMonth(ledger.income(from, to));
        Map<String, BigDecimal> out = byMonth(ledger.spending(from, to));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            String key = YearMonth.of(year, m).toString();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", key);
            row.put("revenue", in.getOrDefault(key, BigDecimal.ZERO));
            row.put("expenses", out.getOrDefault(key, BigDecimal.ZERO));
            rows.add(row);
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> revenueByService(String monthText) {
        YearMonth month = Ledger.month(monthText);
        Map<String, BigDecimal> byCat = new LinkedHashMap<>();
        for (Ledger.Entry e : ledger.income(month.atDay(1), month.plusMonths(1).atDay(1))) {
            byCat.merge(e.category(), e.amount(), BigDecimal::add);
        }
        return byCat.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .map(x -> Map.<String, Object>of("service", x.getKey(), "amount", x.getValue()))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<ActivityView> activity(int limit) {
        return activity.recentBy(CurrentUser.id(), limit);
    }

    /* ══ HMO claims ══ */

    public record ClaimBody(
        @NotBlank(message = "Which organisation?") @Size(max = 200) String organisationName,
        @NotBlank(message = "Which HMO is paying?") @Size(max = 200) String hmoProvider,
        @NotBlank(message = "Pick the month the care was given") @Pattern(regexp = "^\\d{4}-\\d{2}$", message = "Pick the month the care was given") String servicePeriod,
        @Min(value = 0, message = "Whole number of members") Integer members,
        @NotNull(message = "Enter the amount claimed") @DecimalMin(value = "1", message = "Enter the amount claimed") BigDecimal amountClaimed,
        LocalDate submittedAt,
        @Size(max = 2000) String notes) {
    }

    public record ClaimPaidBody(@NotNull(message = "Enter the amount received") @DecimalMin(value = "1", message = "Enter the amount received") BigDecimal amountReceived,
                                LocalDate paidAt, @Size(max = 100) String reference) {
    }

    public record ClaimView(Long id, String claimNumber, String organisationName, Long organisationId, String hmoProvider,
                            String servicePeriod, Integer members, BigDecimal amountClaimed, BigDecimal amountReceived,
                            String status, LocalDate submittedAt, String notes, int followUpCount, Instant lastFollowUpAt,
                            String lastFollowUpNote, LocalDate paidAt, String reference, Instant createdAt) {
        static ClaimView of(HmoClaim c) {
            return new ClaimView(c.getId(), c.getClaimNumber(), c.getOrganisationName(), c.getOrganisationId(),
                c.getHmoProvider(), c.getServicePeriod(), c.getMembers(), c.getAmountClaimed(), c.getAmountReceived(),
                c.getStatus(), c.getSubmittedAt(), c.getNotes(), c.getFollowUpCount(), c.getLastFollowUpAt(),
                c.getLastFollowUpNote(), c.getPaidAt(), c.getPaymentReference(), c.getCreatedAt());
        }
    }

    @Transactional(readOnly = true)
    public List<ClaimView> claims(String status) {
        return claims.findAllByOrderBySubmittedAtDescIdDesc().stream()
            .filter(c -> isAll(status) || c.getStatus().equalsIgnoreCase(status.trim()))
            .map(ClaimView::of).toList();
    }

    @Transactional
    public ClaimView createClaim(ClaimBody b) {
        YearMonth period = Ledger.month(b.servicePeriod());
        if (period.isAfter(YearMonth.from(AppTime.today()))) {
            throw ApiException.badRequest("The service period can't be in the future.");
        }
        LocalDate submitted = b.submittedAt() == null ? AppTime.today() : b.submittedAt();
        if (submitted.isAfter(AppTime.today())) {
            throw ApiException.badRequest("The submission date can't be in the future.");
        }
        HmoClaim c = new HmoClaim(code("CLM-", true), b.organisationName().trim(), b.hmoProvider().trim());
        c.setServicePeriod(period.toString());
        c.setMembers(b.members());
        c.setAmountClaimed(b.amountClaimed().setScale(2, RoundingMode.HALF_UP));
        c.setSubmittedAt(submitted);
        c.setNotes(BillingService.blankToNull(b.notes()));
        c.setCreatedById(CurrentUser.idOrNull());
        organisations.findAllByOrderByCreatedAtDesc().stream()
            .filter(o -> o.getCompanyName().equalsIgnoreCase(c.getOrganisationName()))
            .findFirst().map(HmoOrganisation::getId).ifPresent(c::setOrganisationId);
        HmoClaim saved = claims.save(c);
        activity.record("HMO_CLAIM_SUBMITTED", "Claimed " + BillingService.naira(saved.getAmountClaimed()) + " from "
            + saved.getHmoProvider() + " for " + saved.getOrganisationName(), "HMO_CLAIM", saved.getId());
        return ClaimView.of(saved);
    }

    @Transactional
    public ClaimView followUp(Long id, String note) {
        HmoClaim c = claim(id);
        if (!CLAIM_OPEN.contains(c.getStatus())) {
            throw ApiException.conflict("This claim is " + c.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        String n = BillingService.blankToNull(note);
        if (n != null && n.length() > 1000) {
            throw ApiException.badRequest("Please keep the note under 1,000 characters.");
        }
        c.setStatus("QUERIED");
        c.setFollowUpCount(c.getFollowUpCount() + 1);
        c.setLastFollowUpAt(Instant.now());
        c.setLastFollowUpNote(n);
        activity.record("HMO_CLAIM_FOLLOWED_UP", "Followed up " + c.getClaimNumber() + " with " + c.getHmoProvider()
            + (n == null ? "" : ": " + n), "HMO_CLAIM", c.getId());
        return ClaimView.of(c);
    }

    @Transactional
    public ClaimView claimPaid(Long id, ClaimPaidBody b) {
        HmoClaim c = claim(id);
        if (!CLAIM_OPEN.contains(c.getStatus())) {
            throw ApiException.conflict("This claim is already " + c.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        LocalDate paidAt = b.paidAt() == null ? AppTime.today() : b.paidAt();
        if (paidAt.isAfter(AppTime.today())) {
            throw ApiException.badRequest("Payment date can't be in the future.");
        }
        BigDecimal amount = b.amountReceived().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(c.getAmountClaimed()) > 0) {
            throw ApiException.badRequest("That's more than the " + BillingService.naira(c.getAmountClaimed()) + " claimed.");
        }
        c.setStatus("PAID");
        c.setAmountReceived(amount);
        c.setPaidAt(paidAt);
        c.setPaymentReference(BillingService.blankToNull(b.reference()));
        activity.record("HMO_CLAIM_PAID", c.getHmoProvider() + " paid " + BillingService.naira(amount) + " on "
            + c.getClaimNumber(), "HMO_CLAIM", c.getId());
        return ClaimView.of(c);
    }

    /* ══ Expense claims (financial requests Admin approved) ══ */

    public record ExpenseView(Long id, String claimNumber, String staffName, String submittedByName, String role,
                              String category, String title, String description, BigDecimal amount, LocalDate neededBy,
                              String attachmentUrl, String status, String adminNote, Instant adminApprovedAt,
                              String note, Instant approvedAt, Instant reviewedAt, Instant submittedAt) {
    }

    @Transactional(readOnly = true)
    public List<ExpenseView> expenseClaims(String status) {
        List<FinancialRequest> list = expenseSource();
        Map<Long, String> urls = files.urlsFor(list.stream().map(FinancialRequest::getAttachmentFileId).toList());
        return list.stream().map(r -> expenseView(r, urls))
            .filter(v -> isAll(status) || v.status().equalsIgnoreCase(status.trim())).toList();
    }

    @Transactional
    public ExpenseView approveExpense(Long id, String note) {
        FinancialRequest r = waitingExpense(id);
        r.setFinanceDecision("APPROVED");
        r.setStatus(FinancialRequest.PAID);
        r.setFinanceNote(BillingService.blankToNull(note));
        r.setFinanceReviewedById(CurrentUser.idOrNull());
        r.setFinanceReviewedAt(Instant.now());
        if (r.getRequestedById() != null) {
            notifications.notify(r.getRequestedById(), "Funds released", BillingService.naira(r.getAmount()) + " for "
                + r.getTitle() + " has been released by Finance.", null);
        }
        activity.record("EXPENSE_CLAIM_APPROVED", "Released " + BillingService.naira(r.getAmount()) + " for "
            + r.getTitle() + " (" + r.getRequestedByName() + ")", "FINANCIAL_REQUEST", r.getId());
        return expenseView(r, files.urlsFor(java.util.Collections.singletonList(r.getAttachmentFileId())));
    }

    @Transactional
    public ExpenseView rejectExpense(Long id, String reason) {
        FinancialRequest r = waitingExpense(id);
        String why = BillingService.blankToNull(reason);
        if (why == null) {
            throw ApiException.badRequest("Say why it's being rejected.");
        }
        r.setFinanceDecision("REJECTED");
        r.setFinanceNote(why);
        r.setFinanceReviewedById(CurrentUser.idOrNull());
        r.setFinanceReviewedAt(Instant.now());
        if (r.getRequestedById() != null) {
            notifications.notify(r.getRequestedById(), "Expense not paid", "Finance didn't release " + r.getTitle()
                + ": " + why, null);
        }
        notifications.notifyRoles(Role.ADMINS, "Expense refused by Finance", r.getTitle() + " ("
            + BillingService.naira(r.getAmount()) + ") was refused: " + why, "/admin/financial-requests");
        activity.record("EXPENSE_CLAIM_REJECTED", "Refused " + r.getTitle() + ": " + why, "FINANCIAL_REQUEST", r.getId());
        return expenseView(r, files.urlsFor(java.util.Collections.singletonList(r.getAttachmentFileId())));
    }

    private List<FinancialRequest> expenseSource() {
        List<FinancialRequest> out = new ArrayList<>(requests.findByStatusOrderByCreatedAtDesc(FinancialRequest.APPROVED));
        out.addAll(requests.findByStatusOrderByCreatedAtDesc(FinancialRequest.PAID));
        out.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return out;
    }

    private FinancialRequest waitingExpense(Long id) {
        FinancialRequest r = requests.findById(id)
            .filter(x -> FinancialRequest.APPROVED.equals(x.getStatus()) || FinancialRequest.PAID.equals(x.getStatus()))
            .orElseThrow(() -> ApiException.notFound("Expense claim not found."));
        if (r.getFinanceDecision() != null) {
            throw ApiException.conflict("This claim has already been " + r.getFinanceDecision().toLowerCase(Locale.ROOT) + ".");
        }
        return r;
    }

    private static ExpenseView expenseView(FinancialRequest r, Map<Long, String> urls) {
        String status = r.getFinanceDecision() == null ? "PENDING" : r.getFinanceDecision();
        return new ExpenseView(r.getId(), "EXP-" + r.getId(), r.getRequestedByName(), r.getRequestedByName(),
            r.getRequestedByRole(), r.getCategory(), r.getTitle(),
            r.getDescription() == null ? r.getTitle() : r.getTitle() + " — " + r.getDescription(), r.getAmount(),
            r.getNeededBy(), urls.get(r.getAttachmentFileId()), status, r.getReviewNote(), r.getReviewedAt(),
            r.getFinanceNote(), "APPROVED".equals(r.getFinanceDecision()) ? r.getFinanceReviewedAt() : null,
            r.getFinanceReviewedAt(), r.getCreatedAt());
    }

    /* ══ Facility bills ══ */

    public record BillPayBody(@NotNull(message = "Enter the amount") @DecimalMin(value = "1", message = "Enter the amount") BigDecimal amount,
                              LocalDate paidAt, String method, @Size(max = 100) String reference) {
    }

    public record BillItem(String description, int quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    public record BillView(Long id, String invoiceNumber, Long facilityId, String facilityName, String facilityType,
                           BigDecimal amount, BigDecimal totalAmount, List<BillItem> items, Long requestId,
                           String patientName, String notes, String description, String attachmentUrl, String status,
                           String reviewNote, Instant approvedAt, Instant paidAt, String paymentReference,
                           String paymentMethod, String bankName, String accountName, String accountNumber,
                           Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public List<BillView> bills(String status) {
        List<FacilityInvoice> list = facilityInvoices.findAllByOrderByCreatedAtDesc().stream()
            .filter(i -> !FacilityInvoice.REJECTED.equals(i.getStatus()))
            .filter(i -> isAll(status) || i.getStatus().equalsIgnoreCase(status.trim()))
            .toList();
        return billViews(list);
    }

    @Transactional
    public BillView payBill(Long id, BillPayBody b) {
        FacilityInvoice i = facilityInvoices.findById(id).orElseThrow(() -> ApiException.notFound("Bill not found."));
        if (!FacilityInvoice.APPROVED.equals(i.getStatus())) {
            throw ApiException.conflict(FacilityInvoice.PAID.equals(i.getStatus()) ? "This bill is already paid."
                : "Admin has to approve this bill before it can be paid.");
        }
        if (b.amount().setScale(2, RoundingMode.HALF_UP).compareTo(i.getTotalAmount()) != 0) {
            throw ApiException.badRequest("Pay the full " + BillingService.naira(i.getTotalAmount()) + ".");
        }
        LocalDate paidAt = b.paidAt() == null ? AppTime.today() : b.paidAt();
        if (paidAt.isAfter(AppTime.today())) {
            throw ApiException.badRequest("Payment date can't be in the future.");
        }
        String method = b.method() == null || b.method().isBlank() ? "BANK_TRANSFER" : b.method().trim().toUpperCase(Locale.ROOT);
        if (!BillingService.METHODS.contains(method)) {
            throw ApiException.badRequest("Choose how it was paid.");
        }
        String ref = BillingService.blankToNull(b.reference());
        i.setStatus(FacilityInvoice.PAID);
        i.setPaidAt(paidAt.equals(AppTime.today()) ? Instant.now() : AppTime.startOf(paidAt).plus(12, ChronoUnit.HOURS));
        i.setPaymentReference(ref == null ? "PAY-" + code("", false) : ref);
        i.setPaymentMethod(method);

        Facility f = facilities.findById(i.getFacilityId()).orElse(null);
        if (f != null && f.getAdminUserId() != null) {
            notifications.notify(f.getAdminUserId(), "Invoice paid", "DiGi Health paid invoice " + i.getInvoiceNumber()
                + " (" + BillingService.naira(i.getTotalAmount()) + ", ref " + i.getPaymentReference() + ").",
                switch (f.getKind()) {
                    case HOSPITAL -> "/hospital/invoices";
                    case PHARMACY -> "/pharmacy/invoices";
                    case LABORATORY -> "/laboratory/invoices";
                });
        }
        activity.record("FACILITY_BILL_PAID", "Paid " + i.getInvoiceNumber() + (f == null ? "" : " to " + f.getName())
            + " — " + BillingService.naira(i.getTotalAmount()), "FACILITY_INVOICE", i.getId());
        return billViews(List.of(i)).get(0);
    }

    private List<BillView> billViews(List<FacilityInvoice> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(FacilityInvoice::getId).toList();
        Map<Long, List<BillItem>> items = facilityItems.findByInvoiceIdInOrderByIdAsc(ids).stream()
            .collect(Collectors.groupingBy(FacilityInvoiceItem::getInvoiceId, LinkedHashMap::new,
                Collectors.mapping(x -> new BillItem(x.getDescription(), x.getQuantity(), x.getUnitPrice(), x.getAmount()),
                    Collectors.toList())));
        Map<Long, Facility> facs = facilities.findAllById(list.stream().map(FacilityInvoice::getFacilityId).distinct().toList())
            .stream().collect(Collectors.toMap(Facility::getId, Function.identity()));
        Map<Long, String> urls = files.urlsFor(list.stream().map(FacilityInvoice::getAttachmentFileId).toList());
        List<BillView> out = new ArrayList<>();
        for (FacilityInvoice i : list) {
            Facility f = facs.get(i.getFacilityId());
            boolean approved = FacilityInvoice.APPROVED.equals(i.getStatus()) || FacilityInvoice.PAID.equals(i.getStatus());
            out.add(new BillView(i.getId(), i.getInvoiceNumber(), i.getFacilityId(), f == null ? null : f.getName(),
                f == null ? null : f.getKind().name(), i.getTotalAmount(), i.getTotalAmount(),
                items.getOrDefault(i.getId(), List.of()), i.getRequestId(), i.getPatientName(), i.getNotes(),
                i.getNotes(), urls.get(i.getAttachmentFileId()), i.getStatus(), i.getReviewNote(),
                approved ? i.getReviewedAt() : null, i.getPaidAt(), i.getPaymentReference(), i.getPaymentMethod(),
                f == null ? null : f.getBankName(), f == null ? null : f.getAccountName(),
                f == null ? null : decrypt(f.getAccountNumber()), i.getCreatedAt()));
        }
        return out;
    }

    /* ══ Budget ══ */

    public record BudgetLineBody(@NotBlank(message = "Name the category") @Size(max = 100) String category,
                                 @NotNull(message = "Enter the amount") @DecimalMin(value = "0", message = "Enter the amount") BigDecimal budgeted) {
    }

    public record BudgetBody(@NotBlank(message = "Pick a month") String month,
                             @NotNull @Size(max = 50, message = "At most 50 categories") List<@jakarta.validation.Valid BudgetLineBody> lines) {
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> budget(String monthText) {
        YearMonth month = Ledger.month(monthText);
        List<BudgetLine> saved = budget.findByMonthOrderByIdAsc(month.toString());
        Map<String, BigDecimal> actual = new HashMap<>();
        Map<String, String> labelOf = new LinkedHashMap<>();
        for (Ledger.Entry e : ledger.spending(month.atDay(1), month.plusMonths(1).atDay(1))) {
            actual.merge(Ledger.key(e.category()), e.amount(), BigDecimal::add);
            labelOf.putIfAbsent(Ledger.key(e.category()), e.category());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<String[]> lines = saved.isEmpty()
            ? DEFAULT_BUDGET.stream().map(c -> new String[] {c, "0"}).toList()
            : saved.stream().map(l -> new String[] {l.getCategory(), l.getBudgeted().toPlainString()}).toList();
        for (String[] l : lines) {
            String k = Ledger.key(l[0]);
            used.add(k);
            rows.add(budgetRow(l[0], new BigDecimal(l[1]), actual.getOrDefault(k, BigDecimal.ZERO)));
        }
        /* spending in categories nobody budgeted for */
        for (Map.Entry<String, BigDecimal> e : actual.entrySet()) {
            if (!used.contains(e.getKey())) {
                rows.add(budgetRow(labelOf.get(e.getKey()), BigDecimal.ZERO, e.getValue()));
            }
        }
        return rows;
    }

    @Transactional
    public List<Map<String, Object>> setBudget(BudgetBody b) {
        YearMonth month = Ledger.month(b.month());
        Map<String, BudgetLineBody> wanted = new LinkedHashMap<>();
        for (BudgetLineBody l : b.lines()) {
            String k = l.category().trim().toLowerCase(Locale.ROOT);
            if (wanted.put(k, l) != null) {
                throw ApiException.badRequest("\"" + l.category().trim() + "\" is listed twice.");
            }
        }
        List<BudgetLine> existing = budget.findByMonthOrderByIdAsc(month.toString());
        Map<String, BudgetLine> byKey = existing.stream()
            .collect(Collectors.toMap(l -> l.getCategory().toLowerCase(Locale.ROOT), Function.identity(), (a, c) -> a));
        /* update in place and delete the rest first, so the unique (month, category) index never clashes */
        for (BudgetLine l : existing) {
            if (!wanted.containsKey(l.getCategory().toLowerCase(Locale.ROOT))) {
                budget.delete(l);
            }
        }
        budget.flush();
        for (Map.Entry<String, BudgetLineBody> e : wanted.entrySet()) {
            BigDecimal amount = e.getValue().budgeted().setScale(2, RoundingMode.HALF_UP);
            BudgetLine l = byKey.get(e.getKey());
            if (l == null) {
                budget.save(new BudgetLine(month.toString(), e.getValue().category().trim(), amount));
            } else {
                l.setCategory(e.getValue().category().trim());
                l.setBudgeted(amount);
            }
        }
        activity.record("BUDGET_SET", "Set the " + month + " budget (" + wanted.size() + " categories)", "BUDGET", null);
        budget.flush();
        return budget(month.toString());
    }

    private static Map<String, Object> budgetRow(String category, BigDecimal budgeted, BigDecimal actual) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category", category);
        m.put("budgeted", budgeted);
        m.put("actual", actual);
        m.put("variance", budgeted.subtract(actual));
        m.put("usedPercent", budgeted.signum() > 0 ? pct(actual, budgeted) : null);
        return m;
    }

    /* ══ Reports ══ */

    @Transactional(readOnly = true)
    public Map<String, Object> report(String type, String fromText, String toText) {
        LocalDate from = date(fromText, AppTime.today().withDayOfMonth(1));
        LocalDate to = date(toText, AppTime.today());
        if (to.isBefore(from)) {
            throw ApiException.badRequest("'To' must be on or after 'From'.");
        }
        if (ChronoUnit.DAYS.between(from, to) > 3 * 366) {
            throw ApiException.badRequest("Reports can cover up to 3 years.");
        }
        LocalDate end = to.plusDays(1);
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        Report r = switch (t) {
            case "PROFIT_AND_LOSS" -> profitAndLoss(from, end);
            case "REVENUE_SUMMARY" -> revenueSummary(from, end);
            case "EXPENSES" -> expenses(from, end);
            case "INVOICE_AGEING" -> ageing(to);
            case "HMO_CLAIMS" -> hmoClaims(from, end);
            case "PAYROLL" -> payrollReport(from, to);
            case "BUDGET_VS_ACTUAL" -> budgetVsActual(from, to);
            case "CASH_FLOW" -> cashFlow(from, end);
            default -> throw ApiException.badRequest("Choose a report.");
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", t);
        out.put("from", from);
        out.put("to", to);
        out.put("columns", r.columns());
        out.put("rows", r.rows());
        return out;
    }

    private record Report(List<Map<String, String>> columns, List<Map<String, Object>> rows) {
    }

    private static List<Map<String, String>> cols(String... keyLabel) {
        List<Map<String, String>> out = new ArrayList<>();
        for (int i = 0; i < keyLabel.length; i += 2) {
            out.add(Map.of("key", keyLabel[i], "label", keyLabel[i + 1]));
        }
        return out;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Report profitAndLoss(LocalDate from, LocalDate end) {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal in = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : sumBy(ledger.income(from, end)).entrySet()) {
            rows.add(row("section", "Revenue", "item", Ledger.label(e.getKey()), "amount", e.getValue()));
            in = in.add(e.getValue());
        }
        rows.add(row("section", "Revenue", "item", "Total revenue", "amount", in));
        BigDecimal out = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : sumBy(ledger.spending(from, end)).entrySet()) {
            rows.add(row("section", "Expenses", "item", e.getKey(), "amount", e.getValue()));
            out = out.add(e.getValue());
        }
        rows.add(row("section", "Expenses", "item", "Total expenses", "amount", out));
        rows.add(row("section", "Result", "item", "Net profit", "amount", in.subtract(out)));
        return new Report(cols("section", "Section", "item", "Item", "amount", "Amount (₦)"), rows);
    }

    private Report revenueSummary(LocalDate from, LocalDate end) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (Ledger.Entry e : ledger.income(from, end)) {
            counts.computeIfAbsent(e.category(), k -> new long[1])[0]++;
            sums.merge(e.category(), e.amount(), BigDecimal::add);
        }
        List<Map<String, Object>> rows = sums.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .map(e -> row("service", Ledger.label(e.getKey()), "payments", counts.get(e.getKey())[0], "amount", e.getValue()))
            .toList();
        return new Report(cols("service", "Service", "payments", "Payments", "amount", "Amount (₦)"), rows);
    }

    private Report expenses(LocalDate from, LocalDate end) {
        List<Map<String, Object>> rows = ledger.spending(from, end).stream()
            .map(e -> row("date", e.date(), "category", e.category(), "description", e.description(), "payee", e.party(),
                "amount", e.amount()))
            .toList();
        return new Report(cols("date", "Date", "category", "Category", "description", "Description", "payee", "Paid to",
            "amount", "Amount (₦)"), rows);
    }

    private Report ageing(LocalDate asOf) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Invoice i : invoices.findAllByOrderByCreatedAtDesc()) {
            if (!(Invoice.PENDING.equals(i.getStatus()) || Invoice.PARTIAL.equals(i.getStatus()))) {
                continue;
            }
            long late = Math.max(0, ChronoUnit.DAYS.between(i.getDueDate(), asOf));
            String bucket = late == 0 ? "Not yet due" : late <= 30 ? "1–30 days" : late <= 60 ? "31–60 days"
                : late <= 90 ? "61–90 days" : "Over 90 days";
            rows.add(row("invoiceNumber", i.getInvoiceNumber(), "client", i.getClientName(), "issued", Ledger.day(i.getCreatedAt()),
                "dueDate", i.getDueDate(), "total", i.getTotalAmount(), "balance", i.balance(), "daysOverdue", late,
                "bucket", bucket));
        }
        return new Report(cols("invoiceNumber", "Invoice", "client", "Client", "issued", "Issued", "dueDate", "Due",
            "total", "Total (₦)", "balance", "Balance (₦)", "daysOverdue", "Days overdue", "bucket", "Age"), rows);
    }

    private Report hmoClaims(LocalDate from, LocalDate end) {
        List<Map<String, Object>> rows = claims.findAllByOrderBySubmittedAtDescIdDesc().stream()
            .filter(c -> Ledger.in(c.getSubmittedAt(), from, end))
            .map(c -> row("claimNumber", c.getClaimNumber(), "organisation", c.getOrganisationName(), "hmo", c.getHmoProvider(),
                "period", c.getServicePeriod(), "submitted", c.getSubmittedAt(), "claimed", c.getAmountClaimed(),
                "received", c.getAmountReceived(), "status", c.getStatus(), "paidAt", c.getPaidAt()))
            .toList();
        return new Report(cols("claimNumber", "Claim", "organisation", "Organisation", "hmo", "HMO", "period", "Period",
            "submitted", "Submitted", "claimed", "Claimed (₦)", "received", "Received (₦)", "status", "Status",
            "paidAt", "Paid on"), rows);
    }

    private Report payrollReport(LocalDate from, LocalDate to) {
        List<String> months = monthsBetween(from, to);
        List<Map<String, Object>> rows = payrollLines.findByMonthIn(months).stream()
            .sorted((a, b) -> a.getMonth().equals(b.getMonth()) ? a.getStaffName().compareToIgnoreCase(b.getStaffName())
                : a.getMonth().compareTo(b.getMonth()))
            .map(l -> row("month", l.getMonth(), "name", l.getStaffName(), "role", l.getRole(), "basis", l.getPayBasis(),
                "gross", l.getGrossPay(), "allowances", l.getAllowances(), "deductions", l.getDeductions(),
                "net", l.getNetPay(), "status", l.getStatus(), "reference", l.getReference()))
            .toList();
        return new Report(cols("month", "Month", "name", "Name", "role", "Role", "basis", "Pay basis", "gross", "Gross (₦)",
            "allowances", "Allowances (₦)", "deductions", "Deductions (₦)", "net", "Net (₦)", "status", "Status",
            "reference", "Reference"), rows);
    }

    private Report budgetVsActual(LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String m : monthsBetween(from, to)) {
            for (Map<String, Object> line : budget(m)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("month", m);
                r.putAll(line);
                rows.add(r);
            }
        }
        return new Report(cols("month", "Month", "category", "Category", "budgeted", "Budgeted (₦)", "actual", "Actual (₦)",
            "variance", "Variance (₦)", "usedPercent", "Used %"), rows);
    }

    private Report cashFlow(LocalDate from, LocalDate end) {
        Map<String, BigDecimal> in = byMonth(ledger.income(from, end));
        Map<String, BigDecimal> out = byMonth(ledger.spending(from, end));
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (String m : monthsBetween(from, end.minusDays(1))) {
            BigDecimal i = in.getOrDefault(m, BigDecimal.ZERO);
            BigDecimal o = out.getOrDefault(m, BigDecimal.ZERO);
            running = running.add(i).subtract(o);
            rows.add(row("month", m, "inflow", i, "outflow", o, "net", i.subtract(o), "cumulative", running));
        }
        return new Report(cols("month", "Month", "inflow", "Money in (₦)", "outflow", "Money out (₦)", "net", "Net (₦)",
            "cumulative", "Cumulative (₦)"), rows);
    }

    /* ── helpers ── */

    private HmoClaim claim(Long id) {
        return claims.findById(id).orElseThrow(() -> ApiException.notFound("Claim not found."));
    }

    private static boolean isAll(String status) {
        return status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim());
    }

    private static Map<String, BigDecimal> byMonth(List<Ledger.Entry> entries) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Ledger.Entry e : entries) {
            out.merge(YearMonth.from(e.date()).toString(), e.amount(), BigDecimal::add);
        }
        return out;
    }

    private static Map<String, BigDecimal> sumBy(List<Ledger.Entry> entries) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Ledger.Entry e : entries) {
            out.merge(e.category(), e.amount(), BigDecimal::add);
        }
        return out;
    }

    private static List<String> monthsBetween(LocalDate from, LocalDate to) {
        List<String> out = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            out.add(m.toString());
        }
        return out;
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static LocalDate date(String text, LocalDate fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(text.trim().length() > 10 ? text.trim().substring(0, 10) : text.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Dates must look like 2026-04-30.");
        }
    }

    private String decrypt(String enc) {
        if (enc == null) {
            return null;
        }
        try {
            return crypto.decrypt(enc);
        } catch (IllegalStateException e) {
            return enc.matches("\\d{6,20}") ? enc : null;
        }
    }

    private String code(String prefix, boolean yearMonth) {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            if (yearMonth) {
                sb.append(YearMonth.from(AppTime.today()).toString().replace("-", "").substring(2)).append('-');
            }
            for (int i = 0; i < 6; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String n = sb.toString();
            if (!yearMonth || !claims.existsByClaimNumber(n)) {
                return n;
            }
        }
        throw new IllegalStateException("Couldn't generate a unique number");
    }
}
