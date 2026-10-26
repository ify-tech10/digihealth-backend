package com.digihealth.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.common.ApiException;
import com.digihealth.common.CurrentUser;
import com.digihealth.facility.Facility;
import com.digihealth.facility.FacilityInvoice;
import com.digihealth.facility.FacilityInvoiceItem;
import com.digihealth.facility.FacilityInvoiceItemRepository;
import com.digihealth.facility.FacilityInvoiceRepository;
import com.digihealth.facility.FacilityRepository;
import com.digihealth.file.FileService;
import com.digihealth.finance.FinancialRequest;
import com.digihealth.finance.FinancialRequestRepository;
import com.digihealth.notification.NotificationService;
import com.digihealth.payment.Payment;
import com.digihealth.payment.PaymentRepository;
import com.digihealth.user.Role;

/** Admin > Payments and Admin > Financial Requests (staff requests + facility invoices). */
@Service
public class AdminFinanceService {

    private static final Set<Role> FINANCE = Set.of(Role.FINANCE_MANAGER);

    private final PaymentRepository payments;
    private final FinancialRequestRepository requests;
    private final FacilityInvoiceRepository invoices;
    private final FacilityInvoiceItemRepository invoiceItems;
    private final FacilityRepository facilities;
    private final FileService files;
    private final NotificationService notifications;
    private final ActivityService activity;

    public AdminFinanceService(PaymentRepository payments, FinancialRequestRepository requests,
                               FacilityInvoiceRepository invoices, FacilityInvoiceItemRepository invoiceItems,
                               FacilityRepository facilities, FileService files,
                               NotificationService notifications, ActivityService activity) {
        this.payments = payments;
        this.requests = requests;
        this.invoices = invoices;
        this.invoiceItems = invoiceItems;
        this.facilities = facilities;
        this.files = files;
        this.notifications = notifications;
        this.activity = activity;
    }

    public record PaymentView(Long id, String reference, String payerName, BigDecimal amount, String method,
                              String description, String status, Instant paidAt, Instant createdAt) {
    }

    public record FinancialRequestView(Long id, String title, String category, BigDecimal amount, LocalDate neededBy,
                                       String description, String attachmentUrl, Long requestedById,
                                       String requestedByName, String requestedByRole, String status,
                                       String reviewNote, Instant reviewedAt, Instant createdAt) {
    }

    public record InvoiceItemView(String description, int quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    public record InvoiceView(Long id, String invoiceNumber, Long facilityId, String facilityName, String facilityType,
                              BigDecimal amount, List<InvoiceItemView> items, Long requestId, String patientName,
                              String description, String attachmentUrl, String status, String reviewNote,
                              Instant reviewedAt, Instant paidAt, Instant createdAt) {
    }

    /* ── Payments ── */

    @Transactional(readOnly = true)
    public List<PaymentView> payments() {
        return payments.findAllByOrderByCreatedAtDesc().stream()
            .map(p -> new PaymentView(p.getId(), p.getReference(), p.getPayerName(), p.getAmount(), p.getMethod(),
                p.getDescription(), p.getStatus(), p.getPaidAt(), p.getCreatedAt()))
            .toList();
    }

    /* ── Staff financial requests ── */

    @Transactional(readOnly = true)
    public List<FinancialRequestView> financialRequests(String status) {
        List<FinancialRequest> list = all(status)
            ? requests.findAllByOrderByCreatedAtDesc()
            : requests.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase());
        Map<Long, String> urls = files.urlsFor(list.stream().map(FinancialRequest::getAttachmentFileId).toList());
        return list.stream().map(r -> requestView(r, urls)).toList();
    }

    @Transactional
    public void approveRequest(Long id, String note) {
        FinancialRequest r = pendingRequest(id);
        r.setStatus(FinancialRequest.APPROVED);
        review(r, note);
        if (r.getRequestedById() != null) {
            notifications.notify(r.getRequestedById(), "Financial request approved",
                r.getTitle() + " (" + naira(r.getAmount()) + ") was approved.", requesterLink(r));
        }
        notifications.notifyRoles(FINANCE, "Approved request to pay",
            r.getTitle() + " for " + naira(r.getAmount()) + " from " + r.getRequestedByName() + " was approved.",
            "/finance/financial-requests");
        activity.record("FINANCIAL_REQUEST_APPROVED", "Approved " + r.getTitle() + " (" + naira(r.getAmount()) + ")",
            "FINANCIAL_REQUEST", r.getId());
    }

    @Transactional
    public void rejectRequest(Long id, String reason) {
        FinancialRequest r = pendingRequest(id);
        r.setStatus(FinancialRequest.REJECTED);
        review(r, reason);
        if (r.getRequestedById() != null) {
            notifications.notify(r.getRequestedById(), "Financial request declined",
                r.getTitle() + " was declined" + (r.getReviewNote() == null ? "." : ": " + r.getReviewNote()),
                requesterLink(r));
        }
        activity.record("FINANCIAL_REQUEST_REJECTED", "Declined " + r.getTitle(), "FINANCIAL_REQUEST", r.getId());
    }

    /* ── Facility invoices ── */

    @Transactional(readOnly = true)
    public List<InvoiceView> invoices(String status) {
        List<FacilityInvoice> list = all(status)
            ? invoices.findAllByOrderByCreatedAtDesc()
            : invoices.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase());
        List<Long> ids = list.stream().map(FacilityInvoice::getId).toList();
        Map<Long, List<InvoiceItemView>> items = ids.isEmpty() ? Map.of()
            : invoiceItems.findByInvoiceIdInOrderByIdAsc(ids).stream().collect(Collectors.groupingBy(
                FacilityInvoiceItem::getInvoiceId,
                Collectors.mapping(i -> new InvoiceItemView(i.getDescription(), i.getQuantity(), i.getUnitPrice(),
                    i.getAmount()), Collectors.toList())));
        Map<Long, Facility> facs = facilities.findAllById(list.stream().map(FacilityInvoice::getFacilityId)
                .distinct().toList())
            .stream().collect(Collectors.toMap(Facility::getId, Function.identity()));
        Map<Long, String> urls = files.urlsFor(list.stream().map(FacilityInvoice::getAttachmentFileId).toList());

        List<InvoiceView> out = new ArrayList<>();
        for (FacilityInvoice i : list) {
            Facility f = facs.get(i.getFacilityId());
            out.add(new InvoiceView(i.getId(), i.getInvoiceNumber(), i.getFacilityId(), f == null ? null : f.getName(),
                f == null ? null : f.getKind().name(), i.getTotalAmount(), items.getOrDefault(i.getId(), List.of()),
                i.getRequestId(), i.getPatientName(), i.getNotes(), urls.get(i.getAttachmentFileId()), i.getStatus(),
                i.getReviewNote(), i.getReviewedAt(), i.getPaidAt(), i.getCreatedAt()));
        }
        return out;
    }

    @Transactional
    public void approveInvoice(Long id, String note) {
        FacilityInvoice i = pendingInvoice(id);
        i.setStatus(FacilityInvoice.APPROVED);
        i.setReviewNote(AdminUserService.blankToNull(note));
        i.setReviewedById(CurrentUser.idOrNull());
        i.setReviewedAt(Instant.now());

        Facility f = facilities.findById(i.getFacilityId()).orElse(null);
        String who = f == null ? "a facility" : f.getName();
        notifyFacility(f, "Invoice approved", "Invoice " + i.getInvoiceNumber() + " (" + naira(i.getTotalAmount())
            + ") was approved and sent to Finance for payment.");
        notifications.notifyRoles(FINANCE, "New facility bill to pay",
            "Invoice " + i.getInvoiceNumber() + " from " + who + " for " + naira(i.getTotalAmount()) + " was approved.",
            "/finance/facility-bills");
        activity.record("FACILITY_INVOICE_APPROVED", "Approved invoice " + i.getInvoiceNumber() + " from " + who,
            "FACILITY_INVOICE", i.getId());
    }

    @Transactional
    public void rejectInvoice(Long id, String reason) {
        FacilityInvoice i = pendingInvoice(id);
        i.setStatus(FacilityInvoice.REJECTED);
        i.setReviewNote(AdminUserService.blankToNull(reason));
        i.setReviewedById(CurrentUser.idOrNull());
        i.setReviewedAt(Instant.now());

        Facility f = facilities.findById(i.getFacilityId()).orElse(null);
        notifyFacility(f, "Invoice declined", "Invoice " + i.getInvoiceNumber() + " was declined"
            + (i.getReviewNote() == null ? "." : ": " + i.getReviewNote()));
        activity.record("FACILITY_INVOICE_REJECTED", "Declined invoice " + i.getInvoiceNumber()
            + (f == null ? "" : " from " + f.getName()), "FACILITY_INVOICE", i.getId());
    }

    /* ── helpers ── */

    private FinancialRequest pendingRequest(Long id) {
        FinancialRequest r = requests.findById(id).orElseThrow(() -> ApiException.notFound("Request not found."));
        if (!FinancialRequest.PENDING.equals(r.getStatus())) {
            throw ApiException.conflict("This request has already been " + r.getStatus().toLowerCase() + ".");
        }
        return r;
    }

    private FacilityInvoice pendingInvoice(Long id) {
        FacilityInvoice i = invoices.findById(id).orElseThrow(() -> ApiException.notFound("Invoice not found."));
        if (!FacilityInvoice.PENDING.equals(i.getStatus())) {
            throw ApiException.conflict("This invoice has already been " + i.getStatus().toLowerCase() + ".");
        }
        return i;
    }

    private static void review(FinancialRequest r, String note) {
        r.setReviewNote(AdminUserService.blankToNull(note));
        r.setReviewedById(CurrentUser.idOrNull());
        r.setReviewedAt(Instant.now());
    }

    private void notifyFacility(Facility f, String title, String message) {
        if (f != null && f.getAdminUserId() != null) {
            notifications.notify(f.getAdminUserId(), title, message,
                "/" + f.getKind().name().toLowerCase() + "/invoices");
        }
    }

    private static String requesterLink(FinancialRequest r) {
        return Role.FINANCE_MANAGER.name().equals(r.getRequestedByRole())
            ? "/finance/financial-requests" : "/cno/financial-requests";
    }

    private FinancialRequestView requestView(FinancialRequest r, Map<Long, String> urls) {
        return new FinancialRequestView(r.getId(), r.getTitle(), r.getCategory(), r.getAmount(), r.getNeededBy(),
            r.getDescription(), urls.get(r.getAttachmentFileId()), r.getRequestedById(), r.getRequestedByName(),
            r.getRequestedByRole(), r.getStatus(), r.getReviewNote(), r.getReviewedAt(), r.getCreatedAt());
    }

    private static boolean all(String status) {
        return status == null || status.isBlank() || "ALL".equalsIgnoreCase(status);
    }

    static String naira(BigDecimal amount) {
        return amount == null ? "₦0" : "₦" + String.format("%,.0f", amount);
    }
}
