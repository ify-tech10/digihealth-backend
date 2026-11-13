package com.digihealth.labportal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.digihealth.activity.ActivityService;
import com.digihealth.activity.ActivityService.ActivityView;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.file.FileService;
import com.digihealth.notification.NotificationService;
import com.digihealth.patient.Patient;
import com.digihealth.patient.PatientRepository;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

/*
 * Lab scientists (/lab/me) and pharmacists (/pharmacy/me): requests from the medical
 * team, fulfilled at the patient's home. Requests are never deleted; one that can't be
 * fulfilled is declined back to the team. Accepting a request claims it for you.
 */
@Service
public class LabPortalService {

    public static final String LAB = "LAB";
    public static final String PHARMACY = "PHARMACY";
    private static final Set<String> STATUSES = Set.of(ServiceRequest.NEW, ServiceRequest.ACCEPTED,
        ServiceRequest.IN_PROGRESS, ServiceRequest.COMPLETED, ServiceRequest.DECLINED);
    private static final Set<String> PRIORITIES = Set.of("ROUTINE", "URGENT", "STAT");
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final ServiceRequestRepository requests;
    private final CatalogueItemRepository catalogue;
    private final StockMovementRepository movements;
    private final PatientRepository patients;
    private final UserRepository users;
    private final FileService files;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public LabPortalService(ServiceRequestRepository requests, CatalogueItemRepository catalogue,
                            StockMovementRepository movements, PatientRepository patients, UserRepository users,
                            FileService files, NotificationService notifications, ActivityService activity,
                            ObjectMapper json) {
        this.requests = requests;
        this.catalogue = catalogue;
        this.movements = movements;
        this.patients = patients;
        this.users = users;
        this.files = files;
        this.notifications = notifications;
        this.activity = activity;
        this.json = json;
    }

    /** PHARMACIST -> PHARMACY, LAB_SCIENTIST -> LAB. SecurityConfig keeps each role on its own path. */
    public static String myKind() {
        return CurrentUser.hasRole("PHARMACIST") ? PHARMACY : LAB;
    }

    /* ── bodies ── */

    public record PriceLine(@NotNull Object id, BigDecimal unitPrice) {
    }

    public record AcceptBody(@NotNull(message = "Pick a date and time") Instant scheduledAt,
                             List<PriceLine> items, @Size(max = 2000) String note) {
    }

    public record DeclineBody(@NotBlank(message = "Say why you're declining") @Size(max = 1000) String reason) {
    }

    public record StartBody(@Size(max = 2000) String note) {
    }

    /** Lab, multipart: summary, abnormal, file */
    public record ResultsForm(String summary, Boolean abnormal, MultipartFile file) {
    }

    /** Pharmacy, multipart: receivedBy, deliveredAt?, note?, proof? */
    public record DeliverForm(String receivedBy, String deliveredAt, String note, MultipartFile proof) {
    }

    /** For the medical team's portal to raise a request. */
    public record NewServiceRequest(String kind, Long patientId, String priority, LocalDate dueDate, String notes,
                                    List<Map<String, Object>> items) {
    }

    /* ── reads ── */

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Long me = CurrentUser.id();
        List<ServiceRequest> all = requests.visibleTo(myKind(), me);
        LocalDate today = AppTime.today();
        Instant monthStart = ZonedDateTime.now(AppTime.ZONE).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();

        long fresh = 0;
        long inProgress = 0;
        long dueToday = 0;
        long completed = 0;
        BigDecimal value = BigDecimal.ZERO;
        for (ServiceRequest r : all) {
            if (ServiceRequest.NEW.equals(r.getStatus())) {
                fresh++;
            }
            boolean mine = me.equals(r.getHandledById());
            if (mine && (ServiceRequest.ACCEPTED.equals(r.getStatus()) || ServiceRequest.IN_PROGRESS.equals(r.getStatus()))) {
                inProgress++;
            }
            if (r.isOpen() && (today.equals(r.getDueDate())
                || (r.getScheduledAt() != null && today.equals(r.getScheduledAt().atZone(AppTime.ZONE).toLocalDate())))) {
                dueToday++;
            }
            if (mine && ServiceRequest.COMPLETED.equals(r.getStatus()) && r.getCompletedAt() != null
                && !r.getCompletedAt().isBefore(monthStart)) {
                completed++;
                if (r.getTotalAmount() != null) {
                    value = value.add(r.getTotalAmount());
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("newRequests", fresh);
        m.put("inProgress", inProgress);
        m.put("dueToday", dueToday);
        m.put("completedThisMonth", completed);
        m.put("valueThisMonth", value);
        return m;
    }

    @Transactional(readOnly = true)
    public List<ActivityView> activity(int limit) {
        return activity.recentBy(CurrentUser.id(), limit);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        List<ServiceRequest> all = requests.visibleTo(myKind(), CurrentUser.id());
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            String st = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(st)) {
                throw ApiException.badRequest("Unknown status: " + status);
            }
            all = all.stream().filter(r -> st.equals(r.getStatus())).toList();
        }
        return views(all);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long id) {
        return views(List.of(visible(id))).get(0);
    }

    /* ── actions ── */

    @Transactional
    public Map<String, Object> accept(Long id, AcceptBody body) {
        Long me = CurrentUser.id();
        ServiceRequest r = visible(id);
        requireStatus(r, ServiceRequest.NEW, "accepted");
        if (body.scheduledAt().isBefore(Instant.now().minus(10, ChronoUnit.MINUTES))) {
            throw ApiException.badRequest("The " + visit(r) + " time can't be in the past.");
        }
        Map<String, BigDecimal> prices = new HashMap<>();
        for (PriceLine p : body.items() == null ? List.<PriceLine>of() : body.items()) {
            prices.put(String.valueOf(p.id()), p.unitPrice());
        }
        List<Map<String, Object>> lines = lines(r);
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> line : lines) {
            BigDecimal price = prices.get(String.valueOf(line.get("id")));
            if (price == null || price.signum() <= 0) {
                throw ApiException.badRequest("Every " + item(r) + " needs a price — it's recorded for inventory.");
            }
            price = price.setScale(2, RoundingMode.HALF_UP);
            line.put("unitPrice", price);
            total = total.add(price.multiply(BigDecimal.valueOf(quantity(line))));
        }
        r.setLines(write(lines));
        r.setTotalAmount(total);
        r.setScheduledAt(body.scheduledAt());
        r.setAcceptNote(blankToNull(body.note()));
        r.setAcceptedAt(Instant.now());
        r.setHandledById(me);
        r.setStatus(ServiceRequest.ACCEPTED);

        User user = users.findById(me).orElseThrow();
        tellRequester(r, "Request accepted", user.getFullName() + " accepted " + r.getRequestNumber() + " for "
            + r.getPatientName() + " — " + visit(r) + " on "
            + java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, h:mma", Locale.ENGLISH)
                .format(r.getScheduledAt().atZone(AppTime.ZONE)) + ".");
        activity.record(PHARMACY.equals(r.getKind()) ? "DRUG_ORDER_ACCEPTED" : "LAB_REQUEST_ACCEPTED",
            "Accepted " + r.getRequestNumber() + " for " + r.getPatientName(), "SERVICE_REQUEST", r.getId());
        return get(r.getId());
    }

    @Transactional
    public Map<String, Object> decline(Long id, DeclineBody body) {
        ServiceRequest r = visible(id);
        if (!ServiceRequest.NEW.equals(r.getStatus()) && !ServiceRequest.ACCEPTED.equals(r.getStatus())) {
            throw ApiException.conflict("This request is " + label(r.getStatus()) + " and can't be declined now.");
        }
        r.setStatus(ServiceRequest.DECLINED);
        r.setDeclinedAt(Instant.now());
        r.setDeclineReason(body.reason().trim());
        r.setHandledById(CurrentUser.id());

        tellRequester(r, "Request declined", r.getRequestNumber() + " for " + r.getPatientName()
            + " was declined: " + r.getDeclineReason());
        activity.record("SERVICE_REQUEST_DECLINED", "Declined " + r.getRequestNumber(), "SERVICE_REQUEST", r.getId());
        return get(r.getId());
    }

    /** Lab: sample collected. Pharmacy: out for delivery. */
    @Transactional
    public Map<String, Object> start(Long id, StartBody body) {
        ServiceRequest r = mine(id);
        requireStatus(r, ServiceRequest.ACCEPTED, "started");
        r.setStatus(ServiceRequest.IN_PROGRESS);
        r.setStartedAt(Instant.now());
        r.setStartNote(body == null ? null : blankToNull(body.note()));

        boolean pharmacy = PHARMACY.equals(r.getKind());
        if (pharmacy) {
            Long patientUser = patientUserId(r);
            if (patientUser != null) {
                notifications.notify(patientUser, "Your medication is on the way",
                    "DiGi Health's pharmacist is on the way with your order " + r.getRequestNumber() + ".", "/patient");
            }
        }
        activity.record(pharmacy ? "DRUG_ORDER_OUT_FOR_DELIVERY" : "LAB_SAMPLE_COLLECTED",
            (pharmacy ? "Out for delivery: " : "Sample collected: ") + r.getRequestNumber() + " (" + r.getPatientName() + ")",
            "SERVICE_REQUEST", r.getId());
        return get(r.getId());
    }

    @Transactional
    public Map<String, Object> submitResults(Long id, ResultsForm form) {
        ServiceRequest r = mine(id);
        requireKind(r, LAB);
        requireStatus(r, ServiceRequest.IN_PROGRESS, "resulted");
        String summary = blankToNull(form.summary());
        if (summary == null || summary.length() < 10) {
            throw ApiException.badRequest("Add a short summary the medical team can read at a glance.");
        }
        if (summary.length() > 4000) {
            throw ApiException.badRequest("The summary is too long (4,000 characters max).");
        }
        if (form.file() == null || form.file().isEmpty()) {
            throw ApiException.badRequest("Attach the results file (PDF or image).");
        }
        boolean abnormal = Boolean.TRUE.equals(form.abnormal());
        r.setResultFileId(files.upload(form.file(), FileService.Kind.DOCUMENT, CurrentUser.id()).getId());
        r.setResultSummary(summary);
        r.setAbnormal(abnormal);
        r.setCompletedAt(Instant.now());
        r.setStatus(ServiceRequest.COMPLETED);

        String msg = (abnormal ? "ABNORMAL results" : "Results") + " for " + r.getPatientName() + " ("
            + r.getRequestNumber() + "): " + summary;
        tellRequester(r, abnormal ? "Abnormal lab results" : "Lab results ready", msg);
        if (abnormal && r.getRequestedById() != null) {
            /* the requester was told above; abnormal results also reach the rest of the medical team */
            notifications.notifyRoles(Role.CLINICAL_LEADERSHIP, "Abnormal lab results", msg, "/cno");
        }
        activity.record(abnormal ? "LAB_RESULT_ABNORMAL" : "LAB_RESULTED",
            "Results uploaded for " + r.getRequestNumber() + (abnormal ? " (abnormal)" : ""), "SERVICE_REQUEST", r.getId());
        return get(r.getId());
    }

    @Transactional
    public Map<String, Object> deliver(Long id, DeliverForm form) {
        Long me = CurrentUser.id();
        ServiceRequest r = mine(id);
        requireKind(r, PHARMACY);
        if (!ServiceRequest.IN_PROGRESS.equals(r.getStatus()) && !ServiceRequest.ACCEPTED.equals(r.getStatus())) {
            throw ApiException.conflict("This order is " + label(r.getStatus()) + " and can't be marked delivered.");
        }
        String receivedBy = blankToNull(form.receivedBy());
        if (receivedBy == null) {
            throw ApiException.badRequest("Who received the order?");
        }
        if (receivedBy.length() > 150) {
            throw ApiException.badRequest("'Received by' is too long.");
        }
        Instant at = parseInstant(form.deliveredAt());
        if (at.isAfter(Instant.now().plus(10, ChronoUnit.MINUTES))) {
            throw ApiException.badRequest("The delivery time can't be in the future.");
        }
        if (form.proof() != null && !form.proof().isEmpty()) {
            r.setProofFileId(files.upload(form.proof(), FileService.Kind.DOCUMENT, me).getId());
        }
        r.setReceivedBy(receivedBy);
        r.setDeliveryNote(blankToNull(form.note()));
        if (r.getStartedAt() == null) {
            r.setStartedAt(at);
        }
        r.setCompletedAt(at);
        r.setStatus(ServiceRequest.COMPLETED);

        List<String> shortfalls = dispense(r, me);

        tellRequester(r, "Drugs delivered", "Order " + r.getRequestNumber() + " was delivered to "
            + r.getPatientName() + " (received by " + receivedBy + ").");
        activity.record("DRUG_ORDER_DELIVERED", "Delivered " + r.getRequestNumber() + " to " + r.getPatientName()
            + (shortfalls.isEmpty() ? "" : " — stock ran short for " + String.join(", ", shortfalls)),
            "SERVICE_REQUEST", r.getId());
        return get(r.getId());
    }

    /* ── raising a request (medical team) ── */

    @Transactional
    public ServiceRequest send(NewServiceRequest n) {
        String kind = n.kind() == null ? "" : n.kind().trim().toUpperCase(Locale.ROOT);
        if (!LAB.equals(kind) && !PHARMACY.equals(kind)) {
            throw ApiException.badRequest("Choose lab or pharmacy.");
        }
        Patient p = patients.findById(n.patientId()).orElseThrow(() -> ApiException.notFound("Patient not found."));
        User pu = users.findById(p.getUserId()).orElseThrow(() -> ApiException.notFound("Patient not found."));
        String priority = n.priority() == null ? "ROUTINE" : n.priority().trim().toUpperCase(Locale.ROOT);
        if (!PRIORITIES.contains(priority)) {
            throw ApiException.badRequest("Priority must be routine, urgent or stat.");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        int i = 1;
        for (Map<String, Object> raw : n.items() == null ? List.<Map<String, Object>>of() : n.items()) {
            Map<String, Object> line = new LinkedHashMap<>(raw);
            line.put("id", i++);
            lines.add(line);
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest(PHARMACY.equals(kind) ? "Add at least one drug." : "Add at least one test.");
        }
        ServiceRequest r = new ServiceRequest(kind, newNumber(kind), pu.getFullName(), write(lines));
        r.setPriority(priority);
        r.setDueDate(n.dueDate());
        r.setNotes(blankToNull(n.notes()));
        r.setPatientId(p.getId());
        r.setPatientPhone(pu.getPhoneNumber());
        r.setAddress(p.getAddress());
        r.setLocationArea(p.getAreaLga() != null ? p.getAreaLga() : pu.getLocationArea());
        r.setPatientType(p.getPatientType());
        Long me = CurrentUser.idOrNull();
        if (me != null) {
            users.findById(me).ifPresent(u -> {
                r.setRequestedById(u.getId());
                r.setRequestedByName(u.getFullName());
                r.setRequestedByRole(u.getRole().name());
            });
        }
        ServiceRequest saved = requests.save(r);
        notifications.notifyRoles(List.of(PHARMACY.equals(kind) ? Role.PHARMACIST : Role.LAB_SCIENTIST),
            "New " + (PHARMACY.equals(kind) ? "drug order" : "lab request"),
            ("ROUTINE".equals(priority) ? "" : priority + ": ") + saved.getRequestNumber() + " for " + saved.getPatientName() + ".",
            PHARMACY.equals(kind) ? "/pharmacy" : "/lab");
        activity.record("SERVICE_REQUEST_SENT", "Sent " + saved.getRequestNumber() + " for " + saved.getPatientName(),
            "SERVICE_REQUEST", saved.getId());
        return saved;
    }

    /* ── views ── */

    private List<Map<String, Object>> views(List<ServiceRequest> list) {
        List<Long> fileIds = new ArrayList<>();
        List<Long> userIds = new ArrayList<>();
        for (ServiceRequest r : list) {
            fileIds.add(r.getResultFileId());
            fileIds.add(r.getProofFileId());
            if (r.getHandledById() != null) {
                userIds.add(r.getHandledById());
            }
        }
        Map<Long, String> urls = files.urlsFor(fileIds);
        Map<Long, String> names = new HashMap<>();
        users.findAllById(userIds.stream().distinct().toList()).forEach(u -> names.put(u.getId(), u.getFullName()));

        List<Map<String, Object>> out = new ArrayList<>();
        for (ServiceRequest r : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("kind", r.getKind());
            m.put("requestNumber", r.getRequestNumber());
            m.put("status", r.getStatus());
            m.put("priority", r.getPriority());
            put(m, "dueDate", r.getDueDate());
            put(m, "scheduledAt", r.getScheduledAt());
            m.put("createdAt", r.getCreatedAt());
            m.put("patientId", r.getPatientId());
            m.put("patientName", r.getPatientName());
            put(m, "patientPhone", r.getPatientPhone());
            put(m, "address", r.getAddress());
            put(m, "locationArea", r.getLocationArea());
            put(m, "patientType", r.getPatientType());
            m.put("requestedByName", r.getRequestedByName() == null ? "Medical team" : r.getRequestedByName());
            put(m, "requestedByRole", r.getRequestedByRole());
            put(m, "notes", r.getNotes());
            m.put("items", lines(r));
            put(m, "totalAmount", r.getTotalAmount());
            put(m, "handledByName", names.get(r.getHandledById()));
            put(m, "acceptedAt", r.getAcceptedAt());
            put(m, "acceptNote", r.getAcceptNote());
            put(m, "declinedAt", r.getDeclinedAt());
            put(m, "declineReason", r.getDeclineReason());
            put(m, "startedAt", r.getStartedAt());
            put(m, "startNote", r.getStartNote());
            put(m, "completedAt", r.getCompletedAt());
            if (LAB.equals(r.getKind())) {
                put(m, "resultSummary", r.getResultSummary());
                m.put("abnormal", r.isAbnormal());
                put(m, "resultUrl", urls.get(r.getResultFileId()));
            } else {
                put(m, "receivedBy", r.getReceivedBy());
                put(m, "deliveredAt", ServiceRequest.COMPLETED.equals(r.getStatus()) ? r.getCompletedAt() : null);
                put(m, "deliveryNote", r.getDeliveryNote());
                put(m, "proofUrl", urls.get(r.getProofFileId()));
            }
            m.put("updatedAt", r.getUpdatedAt());
            out.add(m);
        }
        return out;
    }

    /* ── helpers ── */

    /** Unclaimed or mine, and of my kind. */
    private ServiceRequest visible(Long id) {
        ServiceRequest r = requests.findByIdAndKind(id, myKind())
            .orElseThrow(() -> ApiException.notFound("Request not found."));
        if (r.getHandledById() != null && !r.getHandledById().equals(CurrentUser.id())) {
            throw ApiException.notFound("Request not found.");
        }
        return r;
    }

    /** Claimed by me. */
    private ServiceRequest mine(Long id) {
        ServiceRequest r = visible(id);
        if (r.getHandledById() == null) {
            throw ApiException.conflict("Accept this request first.");
        }
        return r;
    }

    /** Takes dispensed quantities off stock; returns the drugs that ran short (stock stops at 0). */
    private List<String> dispense(ServiceRequest r, Long me) {
        List<CatalogueItem> items = catalogue.findByKindOrderByNameAsc(PHARMACY);
        List<String> shortfalls = new ArrayList<>();
        for (Map<String, Object> line : lines(r)) {
            CatalogueItem item = match(items, line);
            if (item == null || item.getStock() == null) {
                continue;
            }
            int qty = quantity(line);
            int before = item.getStock();
            int after = Math.max(0, before - qty);
            if (before < qty) {
                shortfalls.add(item.getName());
            }
            item.setStock(after);
            movements.save(new StockMovement(item.getId(), after - before, after,
                "Dispensed for " + r.getRequestNumber(), r.getId(), me));
        }
        return shortfalls;
    }

    private static CatalogueItem match(List<CatalogueItem> items, Map<String, Object> line) {
        Object cid = line.get("catalogueItemId");
        if (cid != null) {
            for (CatalogueItem c : items) {
                if (String.valueOf(c.getId()).equals(String.valueOf(cid))) {
                    return c;
                }
            }
        }
        String name = line.get("name") == null ? "" : String.valueOf(line.get("name")).trim();
        String strength = line.get("strength") == null ? "" : String.valueOf(line.get("strength")).trim();
        for (CatalogueItem c : items) {
            if (c.getName().equalsIgnoreCase(name)
                && (strength.isEmpty() || c.getStrength() == null || c.getStrength().equalsIgnoreCase(strength))) {
                return c;
            }
        }
        return null;
    }

    private Long patientUserId(ServiceRequest r) {
        return r.getPatientId() == null ? null : patients.findById(r.getPatientId()).map(Patient::getUserId).orElse(null);
    }

    private void tellRequester(ServiceRequest r, String title, String message) {
        if (r.getRequestedById() != null) {
            notifications.notify(r.getRequestedById(), title, message, "/cno");
        } else {
            notifications.notifyRoles(Role.CLINICAL_LEADERSHIP, title, message, "/cno");
        }
    }

    private static void requireStatus(ServiceRequest r, String needed, String verb) {
        if (!needed.equals(r.getStatus())) {
            throw ApiException.conflict("This request is " + label(r.getStatus()) + ", so it can't be " + verb + ".");
        }
    }

    private static void requireKind(ServiceRequest r, String kind) {
        if (!kind.equals(r.getKind())) {
            throw ApiException.badRequest(LAB.equals(kind) ? "Results are only for lab requests." : "Delivery is only for drug orders.");
        }
    }

    private static String label(String status) {
        return status.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static String visit(ServiceRequest r) {
        return PHARMACY.equals(r.getKind()) ? "delivery" : "collection";
    }

    private static String item(ServiceRequest r) {
        return PHARMACY.equals(r.getKind()) ? "drug" : "test";
    }

    private static int quantity(Map<String, Object> line) {
        try {
            int q = (int) Math.round(Double.parseDouble(String.valueOf(line.getOrDefault("quantity", 1))));
            return Math.max(q, 1);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String newNumber(String kind) {
        String prefix = PHARMACY.equals(kind) ? "RX-" : "LR-";
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < 6; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            if (!requests.existsByRequestNumber(sb.toString())) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Couldn't generate a unique request number");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lines(ServiceRequest r) {
        try {
            List<Object> raw = json.readValue(r.getLines(), List.class);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : raw == null ? List.of() : raw) {
                if (o instanceof Map<?, ?> m) {
                    out.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
            return out;
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private String write(Object value) {
        return json.writeValueAsString(value);
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid delivery time.");
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
