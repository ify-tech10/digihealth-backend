package com.digihealth.facilityportal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.digihealth.activity.ActivityService;
import com.digihealth.common.ApiException;
import com.digihealth.common.CurrentUser;
import com.digihealth.facility.Facility;
import com.digihealth.facility.FacilityInvoice;
import com.digihealth.facility.FacilityInvoiceItem;
import com.digihealth.facility.FacilityInvoiceItemRepository;
import com.digihealth.facility.FacilityInvoiceRepository;
import com.digihealth.facility.FacilityKind;
import com.digihealth.facility.FacilityRequest;
import com.digihealth.facility.FacilityRequestRepository;
import com.digihealth.facility.LabTest;
import com.digihealth.facility.LabTestRepository;
import com.digihealth.file.FileService;
import com.digihealth.notification.NotificationService;
import com.digihealth.security.FieldCrypto;
import com.digihealth.user.Role;

import tools.jackson.databind.ObjectMapper;

/** Facility profile, the lab test menu and invoices to DiGi Health. */
@Service
public class FacilityAccountService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final FacilityContext ctx;
    private final LabTestRepository tests;
    private final FacilityInvoiceRepository invoices;
    private final FacilityInvoiceItemRepository invoiceItems;
    private final FacilityRequestRepository requests;
    private final FileService files;
    private final FieldCrypto crypto;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public FacilityAccountService(FacilityContext ctx, LabTestRepository tests, FacilityInvoiceRepository invoices,
                                  FacilityInvoiceItemRepository invoiceItems, FacilityRequestRepository requests,
                                  FileService files, FieldCrypto crypto, NotificationService notifications,
                                  ActivityService activity, ObjectMapper json) {
        this.ctx = ctx;
        this.tests = tests;
        this.invoices = invoices;
        this.invoiceItems = invoiceItems;
        this.requests = requests;
        this.files = files;
        this.crypto = crypto;
        this.notifications = notifications;
        this.activity = activity;
        this.json = json;
    }

    /* ══ Profile ══ */

    public record ProfileBody(
        @Size(max = 150) String contactPerson,
        @NotBlank(message = "Enter a phone number")
        @Pattern(regexp = "^\\+?[0-9 ()-]{7,20}$", message = "Enter a valid phone number") String phoneNumber,
        @Email(message = "Enter a valid email") @Size(max = 255) String email,
        @Size(max = 200) String openingHours,
        Boolean acceptingRequests,
        @Min(value = 0, message = "Whole number") @Max(100000) Integer bedsAvailable,
        @Size(max = 50) List<String> deliveryAreas,
        Boolean homeCollection,
        @Size(max = 50) List<String> collectionAreas,
        @Size(max = 100) String bankName,
        @Size(max = 150) String accountName,
        @Pattern(regexp = "^\\d{10}$", message = "NUBAN account numbers have 10 digits") String accountNumber) {
    }

    @Transactional(readOnly = true)
    public Map<String, Object> profile() {
        return profileView(ctx.current());
    }

    @Transactional
    public Map<String, Object> updateProfile(ProfileBody b) {
        Facility f = ctx.current();
        Map<String, Object> details = details(f);

        f.setContactPerson(FacilityRequestService.blankToNull(b.contactPerson()));
        f.setPhoneNumber(b.phoneNumber().trim());
        f.setOpeningHours(FacilityRequestService.blankToNull(b.openingHours()));
        if (b.acceptingRequests() != null) {
            f.setAccepting(b.acceptingRequests());
        }
        /* the login email stays as Admin set it; this is the contact address */
        String email = FacilityRequestService.blankToNull(b.email());
        if (email == null) {
            details.remove("contactEmail");
        } else {
            details.put("contactEmail", email.toLowerCase(java.util.Locale.ROOT));
        }

        switch (f.getKind()) {
            case HOSPITAL -> f.setBedsAvailable(b.bedsAvailable());
            case PHARMACY -> f.setDeliveryAreas(joinAreas(b.deliveryAreas()));
            case LABORATORY -> {
                if (b.homeCollection() != null) {
                    f.setHomeCollection(b.homeCollection());
                }
                f.setCollectionAreas(f.isHomeCollection() ? joinAreas(b.collectionAreas()) : null);
            }
        }

        boolean bankChanged = false;
        if (b.accountNumber() != null && !b.accountNumber().isBlank()) {
            String bank = FacilityRequestService.blankToNull(b.bankName());
            String name = FacilityRequestService.blankToNull(b.accountName());
            if (bank == null) {
                throw ApiException.badRequest("Choose the bank.");
            }
            if (name == null) {
                throw ApiException.badRequest("Enter the account name.");
            }
            f.setBankName(bank);
            f.setAccountName(name);
            f.setAccountNumber(crypto.encrypt(b.accountNumber()));
            bankChanged = true;
        }
        f.setDetails(json.writeValueAsString(details));

        if (bankChanged) {
            /* a changed payout account is worth a second pair of eyes */
            notifications.notifyRoles(Role.ADMINS, "Facility payout account changed",
                f.getName() + " changed its payout account to " + f.getBankName() + " "
                    + mask(b.accountNumber()) + ".", "/admin/" + f.getKind().path);
            activity.record("FACILITY_BANK_CHANGED", f.getName() + " changed its payout account", "FACILITY", f.getId());
        }
        activity.record("FACILITY_PROFILE_UPDATED", f.getName() + " updated its profile", "FACILITY", f.getId());
        return profileView(f);
    }

    private Map<String, Object> profileView(Facility f) {
        Map<String, Object> m = new LinkedHashMap<>(details(f));
        Object contactEmail = m.remove("contactEmail");
        m.put("id", f.getId());
        m.put("kind", f.getKind().name());
        m.put("name", f.getName());
        m.put("address", f.getAddress());
        m.put("locationArea", f.getLocationArea());
        m.putIfAbsent("areaLga", f.getLocationArea());
        m.put("phoneNumber", f.getPhoneNumber());
        m.put("email", contactEmail != null ? contactEmail : f.getEmail());
        m.put("loginEmail", f.getEmail());
        m.put("contactPerson", f.getContactPerson());
        m.put("openingHours", f.getOpeningHours());
        m.put("acceptingRequests", f.isAccepting());
        if (f.getKind() == FacilityKind.HOSPITAL) {
            m.put("bedsAvailable", f.getBedsAvailable());
        }
        if (f.getKind() == FacilityKind.PHARMACY) {
            m.put("deliveryAreas", splitAreas(f.getDeliveryAreas()));
            m.putIfAbsent("nafdacRegNo", f.getRegistrationNumber());
        }
        if (f.getKind() == FacilityKind.LABORATORY) {
            m.put("homeCollection", f.isHomeCollection());
            m.put("collectionAreas", splitAreas(f.getCollectionAreas()));
            m.putIfAbsent("mlscnRegNo", f.getRegistrationNumber());
        }
        m.put("registrationNumber", f.getRegistrationNumber());
        m.put("bankName", f.getBankName());
        m.put("accountName", f.getAccountName());
        m.put("accountNumber", f.getAccountNumber() == null ? null : mask(decryptOrNull(f.getAccountNumber())));
        m.put("active", f.isActive());
        m.put("createdAt", f.getCreatedAt());
        return m;
    }

    /* ══ Lab test menu ══ */

    public record TestBody(
        @NotBlank(message = "Enter the test name") @Size(max = 150) String name,
        @Size(max = 30) String code,
        @Size(max = 40) String category,
        @Size(max = 30) String sampleType,
        @NotNull(message = "Enter a price") @DecimalMin(value = "1", message = "Enter a price") BigDecimal price,
        @NotNull(message = "Hours until results") @Min(value = 1, message = "Hours until results")
        @Max(value = 2160, message = "At most 90 days") Integer turnaroundHours,
        Boolean fasting,
        Boolean homeCollection,
        Boolean active,
        @Size(max = 2000) String preparation) {
    }

    public record TestView(Long id, String name, String code, String category, String sampleType, BigDecimal price,
                           int turnaroundHours, boolean fasting, boolean homeCollection, boolean active,
                           String preparation, Instant updatedAt) {
        static TestView of(LabTest t) {
            return new TestView(t.getId(), t.getName(), t.getCode(), t.getCategory(), t.getSampleType(), t.getPrice(),
                t.getTurnaroundHours(), t.isFasting(), t.isHomeCollection(), t.isActive(), t.getPreparation(),
                t.getUpdatedAt());
        }
    }

    @Transactional(readOnly = true)
    public List<TestView> tests() {
        Facility f = ctx.current(FacilityKind.LABORATORY);
        return tests.findByFacilityIdOrderByNameAsc(f.getId()).stream().map(TestView::of).toList();
    }

    @Transactional
    public TestView createTest(TestBody b) {
        Facility f = ctx.current(FacilityKind.LABORATORY);
        String name = b.name().trim();
        if (tests.existsByFacilityIdAndNameIgnoreCase(f.getId(), name)) {
            throw ApiException.conflict("\"" + name + "\" is already on your menu.");
        }
        LabTest t = new LabTest(f.getId());
        apply(t, b);
        LabTest saved = tests.save(t);
        activity.record("LAB_TEST_ADDED", f.getName() + " added " + saved.getName() + " to its menu", "LAB_TEST", saved.getId());
        return TestView.of(saved);
    }

    @Transactional
    public TestView updateTest(Long id, TestBody b) {
        Facility f = ctx.current(FacilityKind.LABORATORY);
        LabTest t = tests.findByIdAndFacilityId(id, f.getId())
            .orElseThrow(() -> ApiException.notFound("Test not found."));
        String name = b.name().trim();
        if (tests.existsByFacilityIdAndNameIgnoreCaseAndIdNot(f.getId(), name, id)) {
            throw ApiException.conflict("\"" + name + "\" is already on your menu.");
        }
        apply(t, b);
        activity.record("LAB_TEST_UPDATED", f.getName() + " updated " + t.getName(), "LAB_TEST", t.getId());
        return TestView.of(t);
    }

    @Transactional
    public void deleteTest(Long id) {
        Facility f = ctx.current(FacilityKind.LABORATORY);
        LabTest t = tests.findByIdAndFacilityId(id, f.getId())
            .orElseThrow(() -> ApiException.notFound("Test not found."));
        tests.delete(t);
        activity.record("LAB_TEST_REMOVED", f.getName() + " removed " + t.getName() + " from its menu", "LAB_TEST", id);
    }

    private static void apply(LabTest t, TestBody b) {
        t.setName(b.name().trim());
        String code = FacilityRequestService.blankToNull(b.code());
        t.setCode(code == null ? null : code.toUpperCase(java.util.Locale.ROOT));
        t.setCategory(FacilityRequestService.blankToNull(b.category()));
        t.setSampleType(FacilityRequestService.blankToNull(b.sampleType()));
        t.setPrice(b.price().setScale(2, RoundingMode.HALF_UP));
        t.setTurnaroundHours(b.turnaroundHours());
        t.setFasting(Boolean.TRUE.equals(b.fasting()));
        t.setHomeCollection(!Boolean.FALSE.equals(b.homeCollection()));
        t.setActive(!Boolean.FALSE.equals(b.active()));
        t.setPreparation(FacilityRequestService.blankToNull(b.preparation()));
    }

    /* ══ Invoices to DiGi ══ */

    /** Multipart: requestId?, items (JSON [{ description, quantity, unitPrice }]), notes?, attachment? */
    public record InvoiceForm(String requestId, String items, String notes, MultipartFile attachment) {
    }

    public record InvoiceItemView(String description, int quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    public record InvoiceView(Long id, String invoiceNumber, Long requestId, String requestReference, String patientName,
                              BigDecimal totalAmount, List<InvoiceItemView> items, String notes, String attachmentUrl,
                              String status, String reviewNote, String rejectionReason, Instant approvedAt,
                              Instant paidAt, String paymentReference, Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public List<InvoiceView> invoices() {
        return invoiceViews(invoices.findByFacilityIdOrderByCreatedAtDesc(ctx.current().getId()));
    }

    @Transactional
    public InvoiceView createInvoice(InvoiceForm form) {
        Facility f = ctx.current();

        FacilityRequest request = null;
        String rid = FacilityRequestService.blankToNull(form.requestId());
        if (rid != null) {
            Long requestId;
            try {
                requestId = Long.valueOf(rid);
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("Choose the request this invoice is for.");
            }
            request = requests.findByIdAndFacilityId(requestId, f.getId())
                .orElseThrow(() -> ApiException.notFound("Request not found."));
            if (!request.isDone() || FacilityRequest.DECLINED.equals(request.getStatus())) {
                throw ApiException.conflict("You can invoice " + request.getReference() + " once it's completed.");
            }
            if (invoices.existsByRequestIdAndStatusNot(request.getId(), FacilityInvoice.REJECTED)) {
                throw ApiException.conflict(request.getReference() + " has already been invoiced.");
            }
        }

        List<Map<String, Object>> raw = parseItems(form.items());
        if (raw.isEmpty()) {
            throw ApiException.badRequest("Add at least one line to the invoice.");
        }
        if (raw.size() > 50) {
            throw ApiException.badRequest("An invoice can have at most 50 lines.");
        }
        List<Object[]> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> x : raw) {
            String desc = x.get("description") == null ? null : FacilityRequestService.blankToNull(String.valueOf(x.get("description")));
            if (desc == null) {
                throw ApiException.badRequest("Every line needs a description.");
            }
            if (desc.length() > 300) {
                throw ApiException.badRequest("Line descriptions can be up to 300 characters.");
            }
            BigDecimal qty = number(x.get("quantity"), BigDecimal.ONE);
            if (qty.signum() <= 0 || qty.stripTrailingZeros().scale() > 0 || qty.compareTo(BigDecimal.valueOf(10000)) > 0) {
                throw ApiException.badRequest("Quantity must be a whole number from 1 to 10,000.");
            }
            BigDecimal price = number(x.get("unitPrice"), null);
            if (price == null || price.signum() < 0) {
                throw ApiException.badRequest("Enter a unit price for \"" + desc + "\".");
            }
            price = price.setScale(2, RoundingMode.HALF_UP);
            lines.add(new Object[] {desc, qty.intValue(), price});
            total = total.add(price.multiply(qty));
        }
        if (total.signum() <= 0) {
            throw ApiException.badRequest("The invoice total must be more than ₦0.");
        }
        String notes = FacilityRequestService.limit(FacilityRequestService.blankToNull(form.notes()), 4000, "Notes");

        FacilityInvoice inv = new FacilityInvoice(f.getId(), newInvoiceNumber(), total.setScale(2, RoundingMode.HALF_UP));
        if (request != null) {
            inv.setRequestId(request.getId());
            inv.setPatientName(request.getPatientName());
        }
        inv.setNotes(notes);
        if (form.attachment() != null && !form.attachment().isEmpty()) {
            inv.setAttachmentFileId(files.upload(form.attachment(), FileService.Kind.DOCUMENT, CurrentUser.id()).getId());
        }
        FacilityInvoice saved = invoices.save(inv);
        invoiceItems.saveAll(lines.stream()
            .map(l -> new FacilityInvoiceItem(saved.getId(), (String) l[0], (Integer) l[1], (BigDecimal) l[2]))
            .toList());

        notifications.notifyRoles(Role.ADMINS, "New facility invoice",
            f.getName() + " sent invoice " + saved.getInvoiceNumber() + " for " + naira(saved.getTotalAmount())
                + (request == null ? "." : " (" + request.getReference() + ")."), "/admin/invoices");
        activity.record("FACILITY_INVOICE_CREATED", f.getName() + " sent invoice " + saved.getInvoiceNumber()
            + " for " + naira(saved.getTotalAmount()), "FACILITY_INVOICE", saved.getId());
        return invoiceViews(List.of(saved)).get(0);
    }

    private List<InvoiceView> invoiceViews(List<FacilityInvoice> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(FacilityInvoice::getId).toList();
        Map<Long, List<InvoiceItemView>> items = invoiceItems.findByInvoiceIdInOrderByIdAsc(ids).stream()
            .collect(Collectors.groupingBy(FacilityInvoiceItem::getInvoiceId, LinkedHashMap::new,
                Collectors.mapping(i -> new InvoiceItemView(i.getDescription(), i.getQuantity(), i.getUnitPrice(),
                    i.getAmount()), Collectors.toList())));
        Map<Long, String> refs = requests.findAllById(list.stream().map(FacilityInvoice::getRequestId)
                .filter(Objects::nonNull).distinct().toList()).stream()
            .collect(Collectors.toMap(FacilityRequest::getId, FacilityRequest::getReference));
        Map<Long, String> urls = files.urlsFor(list.stream().map(FacilityInvoice::getAttachmentFileId).toList());

        List<InvoiceView> out = new ArrayList<>();
        for (FacilityInvoice i : list) {
            boolean approved = FacilityInvoice.APPROVED.equals(i.getStatus()) || FacilityInvoice.PAID.equals(i.getStatus());
            boolean rejected = FacilityInvoice.REJECTED.equals(i.getStatus());
            out.add(new InvoiceView(i.getId(), i.getInvoiceNumber(), i.getRequestId(), refs.get(i.getRequestId()),
                i.getPatientName(), i.getTotalAmount(), items.getOrDefault(i.getId(), List.of()), i.getNotes(),
                urls.get(i.getAttachmentFileId()), i.getStatus(), i.getReviewNote(),
                rejected ? i.getReviewNote() : null, approved ? i.getReviewedAt() : null, i.getPaidAt(),
                i.getPaymentReference(), i.getCreatedAt()));
        }
        return out;
    }

    /* ── helpers ── */

    @SuppressWarnings("unchecked")
    private Map<String, Object> details(Facility f) {
        if (f.getDetails() == null || f.getDetails().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = json.readValue(f.getDetails(), Map.class);
            return m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseItems(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = json.readValue(text, List.class);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : raw == null ? List.of() : raw) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw ApiException.badRequest("Invoice lines weren't sent correctly.");
                }
                out.add((Map<String, Object>) m);
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Invoice lines weren't sent correctly.");
        }
    }

    private static BigDecimal number(Object o, BigDecimal fallback) {
        if (o == null || String.valueOf(o).isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("\"" + o + "\" isn't a number.");
        }
    }

    private String newInvoiceNumber() {
        String month = DateTimeFormatter.ofPattern("yyMM").format(ZonedDateTime.now(FacilityRequestService.LAGOS));
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder("INV-").append(month).append('-');
            for (int i = 0; i < 5; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            if (!invoices.existsByInvoiceNumber(sb.toString())) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Couldn't generate a unique invoice number");
    }

    private String decryptOrNull(String stored) {
        try {
            return crypto.decrypt(stored);
        } catch (IllegalStateException e) {
            /* stored before encryption was added (plain digits) */
            return stored.matches("\\d{6,20}") ? stored : null;
        }
    }

    private static String mask(String acct) {
        return acct == null || acct.length() < 4 ? null : "******" + acct.substring(acct.length() - 4);
    }

    private static String joinAreas(List<String> areas) {
        if (areas == null) {
            return null;
        }
        String joined = areas.stream().filter(Objects::nonNull).map(String::trim).filter(a -> !a.isEmpty())
            .map(a -> a.length() > 60 ? a.substring(0, 60) : a).distinct().collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    private static List<String> splitAreas(String text) {
        return text == null || text.isBlank() ? List.of()
            : Arrays.stream(text.split(",")).map(String::trim).filter(a -> !a.isEmpty()).toList();
    }

    private static String naira(BigDecimal amount) {
        return "₦" + String.format("%,.0f", amount);
    }
}
