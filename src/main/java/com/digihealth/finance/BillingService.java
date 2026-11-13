package com.digihealth.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.config.AppProperties;
import com.digihealth.email.EmailService;
import com.digihealth.hmo.HmoOrganisation;
import com.digihealth.hmo.HmoOrganisationRepository;
import com.digihealth.notification.NotificationService;
import com.digihealth.payment.Payment;
import com.digihealth.payment.PaymentRepository;
import com.digihealth.payment.PaystackClient;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/*
 * Invoices DiGi sends (patients and companies) and the money received against them:
 *   - Finance creates invoices, records payments, sends reminders, cancels.
 *   - Patients see their invoices and pay online through Paystack; the payment is
 *     confirmed by Paystack's webhook, or by checking with Paystack when the patient returns.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    public static final Set<String> CATEGORIES = Set.of("HOME_NURSING", "HMO_SUBSCRIPTION", "PHYSIOTHERAPY",
        "LAB_SERVICES", "TELEMEDICINE", "POSTNATAL", "ELDERLY_CARE", "PHARMACY", "OTHER");
    public static final Set<String> METHODS = Set.of("BANK_TRANSFER", "CASH", "CARD", "CHEQUE", "USSD", "PAYSTACK");
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final UserRepository users;
    private final HmoOrganisationRepository organisations;
    private final PaystackClient paystack;
    private final EmailService email;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final String frontendUrl;
    private final SecureRandom random = new SecureRandom();

    public BillingService(InvoiceRepository invoices, PaymentRepository payments, UserRepository users,
                          HmoOrganisationRepository organisations, PaystackClient paystack, EmailService email,
                          NotificationService notifications, ActivityService activity, AppProperties props) {
        this.invoices = invoices;
        this.payments = payments;
        this.users = users;
        this.organisations = organisations;
        this.paystack = paystack;
        this.email = email;
        this.notifications = notifications;
        this.activity = activity;
        this.frontendUrl = props.frontendUrl() == null ? "" : props.frontendUrl().replaceAll("/+$", "");
    }

    /* ── bodies & views ── */

    public record InvoiceBody(
        @NotBlank(message = "Choose patient or organisation") String clientType,
        @NotBlank(message = "Who is this invoice for?") @Size(max = 200) String clientName,
        @Email(message = "Enter a valid email") @Size(max = 255) String clientEmail,
        @NotBlank(message = "Describe the service") @Size(max = 300) String service,
        String category,
        @NotNull(message = "Enter the amount in naira") @DecimalMin(value = "1", message = "Enter the amount in naira") BigDecimal amount,
        @DecimalMin(value = "0", message = "VAT must be between 0 and 100") @DecimalMax(value = "100", message = "VAT must be between 0 and 100") BigDecimal vatPercent,
        @NotNull(message = "Pick a due date") LocalDate dueDate,
        @Size(max = 2000) String notes,
        Long careRequestId) {
    }

    public record PaymentBody(
        Long invoiceId,
        @NotBlank(message = "Who paid?") @Size(max = 200) String payerName,
        @NotNull(message = "Enter the amount received") @DecimalMin(value = "1", message = "Enter the amount received") BigDecimal amount,
        @NotNull(message = "When was it paid?") LocalDate paymentDate,
        @NotBlank(message = "Choose how it was paid") String method,
        @Size(max = 100) String reference,
        @Size(max = 300) String notes) {
    }

    public record LineView(String description, int quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    public record InvoiceView(Long id, String invoiceNumber, String clientType, String clientName, String clientEmail,
                              Long patientUserId, Long organisationId, String service, String description,
                              String category, String serviceType, BigDecimal amount, BigDecimal vatPercent,
                              BigDecimal vatAmount, BigDecimal totalAmount, BigDecimal amountPaid, BigDecimal balance,
                              String status, Instant issuedAt, LocalDate dueDate, String notes, String cancelReason,
                              Instant cancelledAt, Instant paidAt, int reminderCount, Instant lastRemindedAt,
                              List<LineView> items) {
    }

    public record PaymentView(Long id, String reference, String payerName, BigDecimal amount, String method,
                              Instant paymentDate, Instant paidAt, Long invoiceId, String invoiceNumber,
                              String description, String status, Instant createdAt) {
    }

    /* ══ Finance: invoices ══ */

    @Transactional(readOnly = true)
    public List<InvoiceView> invoices(String status) {
        List<Invoice> list = invoices.findAllByOrderByCreatedAtDesc();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            String st = status.trim().toUpperCase(Locale.ROOT);
            LocalDate today = AppTime.today();
            list = list.stream().filter(i -> "OVERDUE".equals(st)
                ? isOverdue(i, today)
                : st.equals(i.getStatus())).toList();
        }
        return list.stream().map(BillingService::view).toList();
    }

    @Transactional
    public InvoiceView createInvoice(InvoiceBody b) {
        String type = b.clientType().trim().toUpperCase(Locale.ROOT);
        if (!"PATIENT".equals(type) && !"ORGANISATION".equals(type)) {
            throw ApiException.badRequest("Choose patient or organisation.");
        }
        String category = b.category() == null || b.category().isBlank() ? "OTHER" : b.category().trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw ApiException.badRequest("Choose a category.");
        }
        if (b.dueDate().isBefore(AppTime.today())) {
            throw ApiException.badRequest("Due date can't be in the past.");
        }
        BigDecimal amount = b.amount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal vatPct = b.vatPercent() == null ? BigDecimal.ZERO : b.vatPercent().setScale(2, RoundingMode.HALF_UP);
        BigDecimal vat = amount.multiply(vatPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        Invoice i = new Invoice(newNumber("DH-", true), type, b.clientName().trim());
        String mail = b.clientEmail() == null || b.clientEmail().isBlank() ? null : User.normalizeEmail(b.clientEmail());
        i.setClientEmail(mail);
        i.setService(b.service().trim());
        i.setCategory(category);
        i.setAmount(amount);
        i.setVatPercent(vatPct);
        i.setVatAmount(vat);
        i.setTotalAmount(amount.add(vat));
        i.setDueDate(b.dueDate());
        i.setNotes(blankToNull(b.notes()));
        i.setCareRequestId(b.careRequestId());
        i.setCreatedById(CurrentUser.idOrNull());
        /* link to the patient's / company's account so it shows in their portal */
        if ("PATIENT".equals(type) && mail != null) {
            users.findByEmail(mail).filter(u -> u.getRole() == Role.PATIENT).ifPresent(u -> i.setPatientUserId(u.getId()));
        } else if ("ORGANISATION".equals(type)) {
            organisations.findAllByOrderByCreatedAtDesc().stream()
                .filter(o -> o.getCompanyName().equalsIgnoreCase(i.getClientName())
                    || (mail != null && mail.equalsIgnoreCase(o.getEmail())))
                .findFirst().map(HmoOrganisation::getId).ifPresent(i::setOrganisationId);
        }
        Invoice saved = invoices.save(i);

        if (saved.getPatientUserId() != null) {
            notifications.notify(saved.getPatientUserId(), "New bill", "Invoice " + saved.getInvoiceNumber() + " for "
                + naira(saved.getTotalAmount()) + " is due " + day(saved.getDueDate()) + ".", "/patient/payments");
        }
        sendInvoiceEmail(saved, false);
        activity.record("INVOICE_CREATED", "Invoice " + saved.getInvoiceNumber() + " to " + saved.getClientName()
            + " for " + naira(saved.getTotalAmount()), "INVOICE", saved.getId());
        return view(saved);
    }

    @Transactional
    public InvoiceView remind(Long id) {
        Invoice i = find(id);
        if (Invoice.PAID.equals(i.getStatus()) || Invoice.CANCELLED.equals(i.getStatus())) {
            throw ApiException.conflict("This invoice is " + i.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        if (i.getClientEmail() == null && i.getPatientUserId() == null) {
            throw ApiException.badRequest("There's no email on this invoice to send a reminder to.");
        }
        if (i.getLastRemindedAt() != null && i.getLastRemindedAt().isAfter(Instant.now().minus(12, ChronoUnit.HOURS))) {
            throw ApiException.tooManyRequests("A reminder was sent in the last 12 hours. Try again later.");
        }
        sendInvoiceEmail(i, true);
        if (i.getPatientUserId() != null) {
            notifications.notify(i.getPatientUserId(), "Payment reminder", "Invoice " + i.getInvoiceNumber() + " — "
                + naira(i.balance()) + " is due " + day(i.getDueDate()) + ".", "/patient/payments");
        }
        i.setReminderCount(i.getReminderCount() + 1);
        i.setLastRemindedAt(Instant.now());
        activity.record("INVOICE_REMINDER_SENT", "Sent a reminder for " + i.getInvoiceNumber(), "INVOICE", i.getId());
        return view(i);
    }

    @Transactional
    public InvoiceView cancel(Long id, String reason) {
        Invoice i = find(id);
        if (Invoice.CANCELLED.equals(i.getStatus()) || Invoice.PAID.equals(i.getStatus())) {
            throw ApiException.conflict("This invoice is already " + i.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        if (i.getAmountPaid().signum() > 0) {
            throw ApiException.conflict("Part of this invoice has been paid, so it can't be cancelled.");
        }
        String why = blankToNull(reason);
        if (why == null) {
            throw ApiException.badRequest("Say why it's being cancelled.");
        }
        i.setStatus(Invoice.CANCELLED);
        i.setCancelReason(why);
        i.setCancelledAt(Instant.now());
        if (i.getPatientUserId() != null) {
            notifications.notify(i.getPatientUserId(), "Bill cancelled",
                "Invoice " + i.getInvoiceNumber() + " was cancelled: " + why, "/patient/payments");
        }
        activity.record("INVOICE_CANCELLED", "Cancelled " + i.getInvoiceNumber() + ": " + why, "INVOICE", i.getId());
        return view(i);
    }

    /* ══ Finance: payments ══ */

    @Transactional(readOnly = true)
    public List<PaymentView> payments(String month) {
        List<Payment> list = payments.findByStatusOrderByPaidAtDesc(Payment.PAID);
        if (month != null && !month.isBlank()) {
            java.time.YearMonth ym = Ledger.month(month);
            list = list.stream().filter(p -> {
                LocalDate d = Ledger.day(p.getPaidAt() != null ? p.getPaidAt() : p.getCreatedAt());
                return d != null && java.time.YearMonth.from(d).equals(ym);
            }).toList();
        }
        return paymentViews(list);
    }

    @Transactional
    public PaymentView recordPayment(PaymentBody b) {
        String method = b.method().trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw ApiException.badRequest("Choose how it was paid.");
        }
        if (b.paymentDate().isAfter(AppTime.today())) {
            throw ApiException.badRequest("Payment date can't be in the future.");
        }
        BigDecimal amount = b.amount().setScale(2, RoundingMode.HALF_UP);
        Invoice inv = null;
        if (b.invoiceId() != null) {
            inv = find(b.invoiceId());
            if (Invoice.CANCELLED.equals(inv.getStatus()) || Invoice.PAID.equals(inv.getStatus())) {
                throw ApiException.conflict("Invoice " + inv.getInvoiceNumber() + " is " + inv.getStatus().toLowerCase(Locale.ROOT) + ".");
            }
            if (amount.compareTo(inv.balance()) > 0) {
                throw ApiException.badRequest("That's more than the " + naira(inv.balance()) + " still owed.");
            }
        }
        String ref = blankToNull(b.reference());
        if (ref == null) {
            /* cash, or a transfer with no reference to hand: still traceable */
            ref = newNumber("MANUAL-", false);
        }
        Instant paidAt = AppTime.startOf(b.paymentDate()).plus(12, ChronoUnit.HOURS);
        Payment p = new Payment(b.payerName().trim(), amount, ref);
        p.setMethod(method);
        p.setPaidAt(paidAt);
        p.setRecordedById(CurrentUser.idOrNull());
        p.setDescription(blankToNull(b.notes()));
        if (inv != null) {
            p.setInvoiceId(inv.getId());
            p.setPayerUserId(inv.getPatientUserId());
            if (p.getDescription() == null) {
                p.setDescription("Payment for " + inv.getInvoiceNumber());
            }
            inv.applyPayment(amount, paidAt);
            if (inv.getPatientUserId() != null) {
                notifications.notify(inv.getPatientUserId(), "Payment received", naira(amount) + " received for "
                    + inv.getInvoiceNumber() + (Invoice.PAID.equals(inv.getStatus()) ? " — paid in full." : "."), "/patient/payments");
            }
        }
        Payment saved = payments.save(p);
        activity.record("PAYMENT_RECORDED", "Recorded " + naira(amount) + " from " + saved.getPayerName()
            + (inv == null ? "" : " for " + inv.getInvoiceNumber()), "PAYMENT", saved.getId());
        return paymentViews(List.of(saved)).get(0);
    }

    /* ══ Patient portal ══ */

    /** The signed-in patient's bills (cancelled ones hidden). Checks any unconfirmed online payment first. */
    @Transactional
    public List<InvoiceView> myInvoices() {
        Long me = CurrentUser.id();
        if (paystack.isConfigured()) {
            Instant recent = Instant.now().minus(2, ChronoUnit.DAYS);
            for (Payment p : payments.findByPayerUserIdAndMethodAndStatus(me, "PAYSTACK", "PENDING")) {
                if (p.getCreatedAt().isAfter(recent)) {
                    try {
                        settle(p);
                    } catch (RuntimeException e) {
                        log.warn("Couldn't check Paystack payment {}: {}", p.getReference(), e.getMessage());
                    }
                }
            }
        }
        return invoices.findByPatientUserIdAndStatusNotOrderByCreatedAtDesc(me, Invoice.CANCELLED).stream()
            .map(BillingService::view).toList();
    }

    /** Starts a Paystack payment for the outstanding balance: { authorizationUrl, reference }. */
    @Transactional
    public Map<String, String> startPayment(Long invoiceId) {
        User me = users.findById(CurrentUser.id()).orElseThrow();
        Invoice i = invoices.findById(invoiceId)
            .filter(x -> me.getId().equals(x.getPatientUserId()))
            .orElseThrow(() -> ApiException.notFound("Bill not found."));
        if (Invoice.PAID.equals(i.getStatus()) || Invoice.CANCELLED.equals(i.getStatus()) || i.balance().signum() <= 0) {
            throw ApiException.conflict("This bill has nothing left to pay.");
        }
        String reference = newNumber("DHP-", false);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("invoiceId", i.getId());
        meta.put("invoiceNumber", i.getInvoiceNumber());
        String url = paystack.initialize(me.getEmail(), i.balance(), reference, frontendUrl + "/patient/payments", meta);

        Payment p = new Payment(me.getFullName(), i.balance(), reference);
        p.setMethod("PAYSTACK");
        p.setStatus("PENDING");
        p.setInvoiceId(i.getId());
        p.setPayerUserId(me.getId());
        p.setDescription("Online payment for " + i.getInvoiceNumber());
        payments.save(p);
        activity.record("ONLINE_PAYMENT_STARTED", me.getFullName() + " started paying " + i.getInvoiceNumber(),
            "INVOICE", i.getId());
        Map<String, String> out = new LinkedHashMap<>();
        out.put("authorizationUrl", url);
        out.put("reference", reference);
        return out;
    }

    /* ══ Paystack webhook ══ */

    /** charge.success -> verify with Paystack and settle. Anything else is ignored. */
    @Transactional
    public void handleWebhook(Map<String, Object> event) {
        if (!"charge.success".equals(String.valueOf(event.get("event")))) {
            return;
        }
        Object data = event.get("data");
        if (!(data instanceof Map<?, ?> d) || d.get("reference") == null) {
            return;
        }
        payments.findFirstByReferenceAndMethod(String.valueOf(d.get("reference")), "PAYSTACK")
            .filter(p -> "PENDING".equals(p.getStatus()))
            .ifPresent(this::settle);
    }

    /** Asks Paystack about a pending online payment and applies it once. */
    private void settle(Payment p) {
        PaystackClient.Verification v = paystack.verify(p.getReference());
        if (v.succeeded()) {
            if (v.amount().compareTo(p.getAmount()) < 0) {
                log.warn("Paystack {} paid {} but {} was expected", p.getReference(), v.amount(), p.getAmount());
                return;
            }
            Instant now = Instant.now();
            if (payments.markPaidIfPending(p.getId(), now) == 1) {
                Invoice i = p.getInvoiceId() == null ? null : invoices.findById(p.getInvoiceId()).orElse(null);
                if (i != null) {
                    i.applyPayment(p.getAmount().min(i.balance().max(BigDecimal.ZERO)), now);
                }
                if (p.getPayerUserId() != null) {
                    notifications.notify(p.getPayerUserId(), "Payment successful", naira(p.getAmount()) + " received"
                        + (i == null ? "." : " for " + i.getInvoiceNumber() + ". Thank you."), "/patient/payments");
                }
                notifications.notifyRoles(List.of(Role.FINANCE_MANAGER), "Online payment received",
                    p.getPayerName() + " paid " + naira(p.getAmount()) + " online"
                        + (i == null ? "." : " for " + i.getInvoiceNumber() + "."), "/finance/payments");
                activity.record(null, "ONLINE_PAYMENT_CONFIRMED", p.getPayerName() + " paid " + naira(p.getAmount())
                    + " online (" + p.getReference() + ")", "PAYMENT", p.getId());
            }
        } else if (v.status() != null && Set.of("failed", "abandoned", "reversed").contains(v.status().toLowerCase(Locale.ROOT))
            && p.getCreatedAt().isBefore(Instant.now().minus(1, ChronoUnit.HOURS))) {
            p.setStatus("FAILED");
        }
    }

    /* ── helpers ── */

    private Invoice find(Long id) {
        return invoices.findById(id).orElseThrow(() -> ApiException.notFound("Invoice not found."));
    }

    static boolean isOverdue(Invoice i, LocalDate today) {
        return (Invoice.PENDING.equals(i.getStatus()) || Invoice.PARTIAL.equals(i.getStatus()))
            && i.getDueDate().isBefore(today);
    }

    static InvoiceView view(Invoice i) {
        List<LineView> items = new ArrayList<>();
        items.add(new LineView(i.getService(), 1, i.getAmount(), i.getAmount()));
        if (i.getVatAmount().signum() > 0) {
            items.add(new LineView("VAT (" + i.getVatPercent().stripTrailingZeros().toPlainString() + "%)", 1,
                i.getVatAmount(), i.getVatAmount()));
        }
        return new InvoiceView(i.getId(), i.getInvoiceNumber(), i.getClientType(), i.getClientName(), i.getClientEmail(),
            i.getPatientUserId(), i.getOrganisationId(), i.getService(), i.getService(), i.getCategory(), i.getCategory(),
            i.getAmount(), i.getVatPercent(), i.getVatAmount(), i.getTotalAmount(), i.getAmountPaid(), i.balance(),
            i.getStatus(), i.getCreatedAt(), i.getDueDate(), i.getNotes(), i.getCancelReason(), i.getCancelledAt(),
            i.getPaidAt(), i.getReminderCount(), i.getLastRemindedAt(), items);
    }

    private List<PaymentView> paymentViews(List<Payment> list) {
        Map<Long, String> numbers = invoices.findAllById(list.stream().map(Payment::getInvoiceId)
                .filter(java.util.Objects::nonNull).distinct().toList()).stream()
            .collect(Collectors.toMap(Invoice::getId, Invoice::getInvoiceNumber));
        return list.stream().map(p -> new PaymentView(p.getId(), p.getReference(), p.getPayerName(), p.getAmount(),
            p.getMethod(), p.getPaidAt(), p.getPaidAt(), p.getInvoiceId(), numbers.get(p.getInvoiceId()),
            p.getDescription(), p.getStatus(), p.getCreatedAt())).toList();
    }

    private void sendInvoiceEmail(Invoice i, boolean reminder) {
        String to = i.getClientEmail();
        if (to == null && i.getPatientUserId() != null) {
            to = users.findById(i.getPatientUserId()).map(User::getEmail).orElse(null);
        }
        if (to == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add((reminder ? "This is a reminder that invoice " : "Invoice ") + i.getInvoiceNumber() + " for "
            + i.getService() + (reminder ? " is still open." : " has been issued."));
        lines.add("Amount due: " + naira(i.balance()) + " by " + day(i.getDueDate()) + ".");
        if (i.getPatientUserId() != null) {
            lines.add("You can view and pay it in your DiGi Health account: " + frontendUrl + "/patient/payments");
        } else {
            lines.add("Please pay by bank transfer and quote the invoice number. Reply to this email if you have any questions.");
        }
        email.sendNotice(to, i.getClientName(), (reminder ? "Reminder: " : "") + "Invoice " + i.getInvoiceNumber(),
            reminder ? "Payment reminder" : "Your invoice from DiGi Health", lines);
    }

    /** DH-2611-AB3CD (yearMonth = true) or MANUAL-K7P2Q9XW. */
    private String newNumber(String prefix, boolean yearMonth) {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            if (yearMonth) {
                sb.append(DateTimeFormatter.ofPattern("yyMM").format(ZonedDateTime.now(AppTime.ZONE))).append('-');
            }
            for (int k = 0; k < (yearMonth ? 5 : 8); k++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String n = sb.toString();
            if (yearMonth ? !invoices.existsByInvoiceNumber(n) : payments.findFirstByReferenceAndMethod(n, "PAYSTACK").isEmpty()) {
                return n;
            }
        }
        throw new IllegalStateException("Couldn't generate a unique number");
    }

    static String naira(BigDecimal amount) {
        return "₦" + String.format("%,.0f", amount);
    }

    private static String day(LocalDate d) {
        return DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(d);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
