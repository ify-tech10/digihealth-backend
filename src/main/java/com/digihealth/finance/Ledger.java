package com.digihealth.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.facility.Facility;
import com.digihealth.facility.FacilityInvoice;
import com.digihealth.facility.FacilityInvoiceRepository;
import com.digihealth.facility.FacilityRepository;
import com.digihealth.payment.Payment;
import com.digihealth.payment.PaymentRepository;

/*
 * Money in and money out as one list of dated entries, so the dashboard, charts,
 * budget and reports all add up the same way.
 *   In:  payments received (PAID), HMO claims paid by insurers.
 *   Out: expense claims Finance released, facility bills paid, payroll lines paid.
 */
@Component
public class Ledger {

    public static final String STAFF_SALARIES = "Staff salaries";
    public static final String PROVIDER_PAYOUTS = "Provider payouts";
    public static final String FACILITY_BILLS = "Facility bills";

    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final HmoClaimRepository claims;
    private final FinancialRequestRepository requests;
    private final FacilityInvoiceRepository facilityInvoices;
    private final FacilityRepository facilities;
    private final PayrollLineRepository payroll;

    public Ledger(PaymentRepository payments, InvoiceRepository invoices, HmoClaimRepository claims,
                  FinancialRequestRepository requests, FacilityInvoiceRepository facilityInvoices,
                  FacilityRepository facilities, PayrollLineRepository payroll) {
        this.payments = payments;
        this.invoices = invoices;
        this.claims = claims;
        this.requests = requests;
        this.facilityInvoices = facilityInvoices;
        this.facilities = facilities;
        this.payroll = payroll;
    }

    /** One dated amount. category is a service code for income and a budget label for spending. */
    public record Entry(LocalDate date, String category, String description, String party, BigDecimal amount) {
    }

    @Transactional(readOnly = true)
    public List<Entry> income(LocalDate from, LocalDate toExclusive) {
        List<Entry> out = new ArrayList<>();
        Map<Long, Invoice> inv = invoices.findAll().stream().collect(Collectors.toMap(Invoice::getId, Function.identity()));
        for (Payment p : payments.findByStatusOrderByPaidAtDesc(Payment.PAID)) {
            LocalDate d = day(p.getPaidAt() != null ? p.getPaidAt() : p.getCreatedAt());
            if (in(d, from, toExclusive)) {
                Invoice i = p.getInvoiceId() == null ? null : inv.get(p.getInvoiceId());
                String cat = i != null ? i.getCategory() : p.getHmoSubscriptionId() != null ? "HMO_SUBSCRIPTION" : "OTHER";
                out.add(new Entry(d, cat, p.getDescription() != null ? p.getDescription()
                    : i != null ? i.getService() : "Payment " + p.getReference(), p.getPayerName(), p.getAmount()));
            }
        }
        for (HmoClaim c : claims.findAll()) {
            if ("PAID".equals(c.getStatus()) && c.getPaidAt() != null && in(c.getPaidAt(), from, toExclusive)
                && c.getAmountReceived() != null) {
                out.add(new Entry(c.getPaidAt(), "HMO_CLAIMS", "HMO claim " + c.getClaimNumber(), c.getHmoProvider(),
                    c.getAmountReceived()));
            }
        }
        out.sort(Comparator.comparing(Entry::date));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Entry> spending(LocalDate from, LocalDate toExclusive) {
        List<Entry> out = new ArrayList<>();
        for (FinancialRequest r : requests.findAll()) {
            if ("APPROVED".equals(r.getFinanceDecision()) && r.getFinanceReviewedAt() != null) {
                LocalDate d = day(r.getFinanceReviewedAt());
                if (in(d, from, toExclusive)) {
                    out.add(new Entry(d, label(r.getCategory()), r.getTitle(), r.getRequestedByName(), r.getAmount()));
                }
            }
        }
        Map<Long, String> facilityNames = facilities.findAll().stream()
            .collect(Collectors.toMap(Facility::getId, Facility::getName));
        for (FacilityInvoice i : facilityInvoices.findByStatusOrderByCreatedAtDesc(FacilityInvoice.PAID)) {
            LocalDate d = day(i.getPaidAt() != null ? i.getPaidAt() : i.getUpdatedAt());
            if (in(d, from, toExclusive)) {
                out.add(new Entry(d, FACILITY_BILLS, "Bill " + i.getInvoiceNumber(), facilityNames.get(i.getFacilityId()),
                    i.getTotalAmount()));
            }
        }
        for (PayrollLine l : payroll.findByStatus("PAID")) {
            LocalDate d = day(l.getPaidAt() != null ? l.getPaidAt() : l.getUpdatedAt());
            if (in(d, from, toExclusive)) {
                out.add(new Entry(d, "SALARY".equals(l.getPayBasis()) ? STAFF_SALARIES : PROVIDER_PAYOUTS,
                    "Pay for " + l.getMonth(), l.getStaffName(), l.getNetPay()));
            }
        }
        out.sort(Comparator.comparing(Entry::date));
        return out;
    }

    /* ── shared helpers ── */

    public static BigDecimal total(List<Entry> entries) {
        return entries.stream().map(Entry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** "MEDICAL_SUPPLIES" -> "Medical supplies" (matches the budget's default categories). */
    public static String label(String code) {
        if (code == null || code.isBlank()) {
            return "Other";
        }
        String s = code.trim().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Letters only, lower case: "Lab & diagnostics" and "LAB_DIAGNOSTICS" both become "labdiagnostics". */
    public static String key(String label) {
        return label == null ? "" : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    /** "2026-11" (or blank = this month). */
    public static YearMonth month(String text) {
        if (text == null || text.isBlank()) {
            return YearMonth.from(AppTime.today());
        }
        try {
            return YearMonth.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Month must look like 2026-04.");
        }
    }

    public static LocalDate day(Instant t) {
        return t == null ? null : t.atZone(AppTime.ZONE).toLocalDate();
    }

    public static boolean in(LocalDate d, LocalDate from, LocalDate toExclusive) {
        return d != null && !d.isBefore(from) && d.isBefore(toExclusive);
    }
}
