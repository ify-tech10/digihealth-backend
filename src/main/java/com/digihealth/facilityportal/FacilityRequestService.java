package com.digihealth.facilityportal;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.digihealth.activity.ActivityService;
import com.digihealth.common.ApiException;
import com.digihealth.common.CurrentUser;
import com.digihealth.facility.Facility;
import com.digihealth.facility.FacilityInvoice;
import com.digihealth.facility.FacilityInvoiceRepository;
import com.digihealth.facility.FacilityKind;
import com.digihealth.facility.FacilityRepository;
import com.digihealth.facility.FacilityRequest;
import com.digihealth.facility.FacilityRequestRepository;
import com.digihealth.file.FileService;
import com.digihealth.notification.NotificationService;
import com.digihealth.user.Role;
import com.digihealth.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

/*
 * Referrals (hospital), drug orders (pharmacy) and test requests (lab) as the
 * facility sees and works them. Every action is scoped to the signed-in admin's facility.
 */
@Service
public class FacilityRequestService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final Set<String> STATUSES = Set.of(FacilityRequest.NEW, FacilityRequest.ACCEPTED,
        FacilityRequest.DECLINED, FacilityRequest.ADMITTED, FacilityRequest.DISCHARGED, FacilityRequest.DISPATCHED,
        FacilityRequest.DELIVERED, FacilityRequest.COLLECTED, FacilityRequest.RESULTED);
    private static final Set<String> OUTCOMES = Set.of("RECOVERED", "IMPROVED", "REFERRED_ON", "LEFT_AGAINST_ADVICE");
    private static final Set<String> FLAGS = Set.of("NORMAL", "HIGH", "LOW", "ABNORMAL", "CRITICAL");
    private static final Set<String> URGENCIES = Set.of("ROUTINE", "URGENT", "EMERGENCY");
    private static final Map<FacilityKind, String> PREFIX = Map.of(
        FacilityKind.HOSPITAL, "REF", FacilityKind.PHARMACY, "ORD", FacilityKind.LABORATORY, "LAB");
    private static final Map<FacilityKind, String> NOUN = Map.of(
        FacilityKind.HOSPITAL, "Referral", FacilityKind.PHARMACY, "Drug order", FacilityKind.LABORATORY, "Test request");
    private static final Map<FacilityKind, String> FROM_KEY = Map.of(
        FacilityKind.HOSPITAL, "referredByName", FacilityKind.PHARMACY, "prescribedByName",
        FacilityKind.LABORATORY, "requestedByName");
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final FacilityContext ctx;
    private final FacilityRequestRepository requests;
    private final FacilityRepository facilities;
    private final FacilityInvoiceRepository invoices;
    private final FileService files;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final ObjectMapper json;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public FacilityRequestService(FacilityContext ctx, FacilityRequestRepository requests, FacilityRepository facilities,
                                  FacilityInvoiceRepository invoices, FileService files,
                                  NotificationService notifications, ActivityService activity, ObjectMapper json,
                                  UserRepository users) {
        this.ctx = ctx;
        this.requests = requests;
        this.facilities = facilities;
        this.invoices = invoices;
        this.files = files;
        this.notifications = notifications;
        this.activity = activity;
        this.json = json;
        this.users = users;
    }

    /* ── bodies ── */

    /** One line of an order / test request in the accept form. Pharmacy sends unitPrice, lab sends price. */
    public record LineChoice(Object id, Boolean available, BigDecimal unitPrice, BigDecimal price) {
    }

    /**
     * hospital: { expectedArrival, ward, note }
     * pharmacy: { dispatchAt, items: [{ id, unitPrice, available }], note }
     * lab:      { collectionType, scheduledAt, turnaroundHours, tests: [{ id, price, available }], note }
     */
    public record AcceptBody(Instant expectedArrival, @Size(max = 100) String ward,
                             Instant dispatchAt, List<LineChoice> items,
                             String collectionType, Instant scheduledAt, Integer turnaroundHours, List<LineChoice> tests,
                             @Size(max = 2000) String note) {
    }

    public record DeclineBody(@NotBlank(message = "Say why you're declining") @Size(max = 1000) String reason) {
    }

    public record AdmitBody(@Size(max = 100) String ward, Instant admittedAt) {
    }

    public record DispatchBody(@Size(max = 150) String rider, Instant dispatchedAt) {
    }

    public record CollectBody(@NotBlank(message = "Enter the sample ID") @Size(max = 60) String sampleId,
                              @NotBlank(message = "Who collected the sample?") @Size(max = 150) String collectedBy,
                              Instant collectedAt, Boolean patientFasted, @Size(max = 2000) String note) {
    }

    /** Multipart: summary, outcome, followUp?, file? */
    public record DischargeForm(String summary, String outcome, String followUp, MultipartFile file) {
    }

    /** Multipart: receivedBy, deliveredAt?, note?, proof? */
    public record DeliverForm(String receivedBy, String deliveredAt, String note, MultipartFile proof) {
    }

    /** Multipart: results (JSON [{ testId, value, unit, referenceRange, flag }]), comment?, critical, report */
    public record ResultsForm(String results, String comment, Boolean critical, MultipartFile report) {
    }

    /** For the care team (CNO / pharmacist / lab scientist portals) to send work to a facility. */
    public record NewRequest(Long facilityId, Long patientId, String urgency, Map<String, Object> patient,
                             String reason, String diagnosis, String priorCareSummary, Map<String, Object> vitals,
                             List<String> medications, List<String> allergies, String clinicalNotes,
                             List<Map<String, Object>> lines, Long attachmentFileId) {
    }

    /* ── reads ── */

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Facility f = ctx.current();
        List<FacilityRequest> all = requests.findByFacilityIdOrderByCreatedAtDesc(f.getId());
        List<FacilityInvoice> bills = invoices.findByFacilityIdOrderByCreatedAtDesc(f.getId());
        Instant monthStart = ZonedDateTime.now(LAGOS).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();

        long fresh = all.stream().filter(r -> FacilityRequest.NEW.equals(r.getStatus())).count();
        long inProgress = all.stream().filter(r -> !r.isDone() && !FacilityRequest.NEW.equals(r.getStatus())).count();
        long completed = all.stream().filter(r -> r.isDone() && !FacilityRequest.DECLINED.equals(r.getStatus())
            && r.getUpdatedAt() != null && !r.getUpdatedAt().isBefore(monthStart)).count();
        long declined = all.stream().filter(r -> FacilityRequest.DECLINED.equals(r.getStatus())
            && r.getDeclinedAt() != null && !r.getDeclinedAt().isBefore(monthStart)).count();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", f.getKind().name());
        m.put("accepting", f.isAccepting());
        m.put("newRequests", fresh);
        m.put("inProgress", inProgress);
        m.put("completedThisMonth", completed);
        m.put("declinedThisMonth", declined);
        m.put("pendingInvoices", bills.stream().filter(i -> FacilityInvoice.PENDING.equals(i.getStatus())).count());
        m.put("awaitingPayment", sum(bills.stream().filter(i -> FacilityInvoice.APPROVED.equals(i.getStatus()))));
        m.put("paidThisMonth", sum(bills.stream().filter(i -> FacilityInvoice.PAID.equals(i.getStatus())
            && i.getPaidAt() != null && !i.getPaidAt().isBefore(monthStart))));
        m.put("invoicedThisMonth", sum(bills.stream().filter(i -> !FacilityInvoice.REJECTED.equals(i.getStatus())
            && !i.getCreatedAt().isBefore(monthStart))));
        return m;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        Facility f = ctx.current();
        List<FacilityRequest> list;
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            list = requests.findByFacilityIdOrderByCreatedAtDesc(f.getId());
        } else {
            String st = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(st)) {
                throw ApiException.badRequest("Unknown status: " + status);
            }
            list = requests.findByFacilityIdAndStatusOrderByCreatedAtDesc(f.getId(), st);
        }
        return views(list);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long id) {
        return views(List.of(mine(ctx.current(), id))).get(0);
    }

    /* ── all kinds ── */

    @Transactional
    public Map<String, Object> accept(Long id, AcceptBody body) {
        Facility f = ctx.current();
        FacilityRequest r = mine(f, id);
        requireStatus(r, FacilityRequest.NEW, "accepted");
        if (!f.isAccepting()) {
            throw ApiException.conflict("Your profile says you're not accepting new work. Turn it back on in your profile first.");
        }
        switch (f.getKind()) {
            case HOSPITAL -> {
                if (body.expectedArrival() == null) {
                    throw ApiException.badRequest("When should the patient arrive?");
                }
                r.setExpectedArrival(body.expectedArrival());
                r.setWard(blankToNull(body.ward()));
            }
            case PHARMACY -> {
                if (body.dispatchAt() == null) {
                    throw ApiException.badRequest("When will the order be dispatched?");
                }
                r.setLines(write(applyChoices(lines(r), body.items(), "unitPrice", "item")));
                r.setDispatchAt(body.dispatchAt());
            }
            case LABORATORY -> {
                String type = body.collectionType() == null ? "" : body.collectionType().trim().toUpperCase(Locale.ROOT);
                if (!"HOME".equals(type) && !"WALK_IN".equals(type)) {
                    throw ApiException.badRequest("Choose home collection or walk-in.");
                }
                if (body.scheduledAt() == null) {
                    throw ApiException.badRequest("When will the sample be collected?");
                }
                if (body.turnaroundHours() == null || body.turnaroundHours() < 1 || body.turnaroundHours() > 2160) {
                    throw ApiException.badRequest("Enter the hours until results are ready.");
                }
                r.setLines(write(applyChoices(lines(r), body.tests(), "price", "test")));
                r.setCollectionType(type);
                r.setScheduledAt(body.scheduledAt());
                r.setTurnaroundHours(body.turnaroundHours());
            }
        }
        r.setStatus(FacilityRequest.ACCEPTED);
        r.setAcceptedAt(Instant.now());
        r.setAcceptNote(blankToNull(body.note()));

        tellCareTeam(r, NOUN.get(r.getKind()) + " accepted",
            f.getName() + " accepted " + r.getReference() + " for " + r.getPatientName() + whenText(r) + ".");
        activity.record("FACILITY_REQUEST_ACCEPTED", f.getName() + " accepted " + r.getReference(),
            "FACILITY_REQUEST", r.getId());
        return get(r.getId());
    }

    @Transactional
    public Map<String, Object> decline(Long id, DeclineBody body) {
        Facility f = ctx.current();
        FacilityRequest r = mine(f, id);
        if (!FacilityRequest.NEW.equals(r.getStatus()) && !FacilityRequest.ACCEPTED.equals(r.getStatus())) {
            throw ApiException.conflict("This " + noun(r) + " is already " + r.getStatus().toLowerCase(Locale.ROOT)
                + " and can't be declined now.");
        }
        r.setStatus(FacilityRequest.DECLINED);
        r.setDeclinedAt(Instant.now());
        r.setDeclineReason(body.reason().trim());

        tellCareTeam(r, NOUN.get(r.getKind()) + " declined",
            f.getName() + " declined " + r.getReference() + " for " + r.getPatientName() + ": " + r.getDeclineReason()
                + " Please send it to another " + f.getKind().label.toLowerCase(Locale.ROOT) + ".");
        activity.record("FACILITY_REQUEST_DECLINED", f.getName() + " declined " + r.getReference(),
            "FACILITY_REQUEST", r.getId());
        return get(r.getId());
    }

    /* ── hospital ── */

    @Transactional
    public Map<String, Object> admit(Long id, AdmitBody body) {
        Facility f = ctx.current(FacilityKind.HOSPITAL);
        FacilityRequest r = mine(f, id);
        requireStatus(r, FacilityRequest.ACCEPTED, "admitted");
        Instant at = body == null || body.admittedAt() == null ? Instant.now() : body.admittedAt();
        notFuture(at, "Admission time");
        String ward = body == null ? null : blankToNull(body.ward());
        if (ward != null) {
            r.setWard(ward);
        }
        r.setAdmittedAt(at);
        r.setStatus(FacilityRequest.ADMITTED);

        tellCareTeam(r, "Patient admitted", r.getPatientName() + " was admitted at " + f.getName()
            + (r.getWard() == null ? "" : " (" + r.getWard() + ")") + ".");
        activity.record("REFERRAL_ADMITTED", "Admitted " + r.getPatientName() + " (" + r.getReference() + ")",
            "FACILITY_REQUEST", r.getId());
        return get(r.getId());
    }

    @Transactional
    public Map<String, Object> discharge(Long id, DischargeForm form) {
        Facility f = ctx.current(FacilityKind.HOSPITAL);
        FacilityRequest r = mine(f, id);
        requireStatus(r, FacilityRequest.ADMITTED, "discharged");
        String summary = form.summary() == null ? "" : form.summary().trim();
        if (summary.length() < 20) {
            throw ApiException.badRequest("Write a short discharge summary (at least 20 characters).");
        }
        if (summary.length() > 8000) {
            throw ApiException.badRequest("The discharge summary is too long (8,000 characters max).");
        }
        String outcome = form.outcome() == null ? "" : form.outcome().trim().toUpperCase(Locale.ROOT);
        if (!OUTCOMES.contains(outcome)) {
            throw ApiException.badRequest("Choose the discharge outcome.");
        }
        r.setDischargeSummary(summary);
        r.setDischargeOutcome(outcome);
        r.setFollowUp(limit(blankToNull(form.followUp()), 4000, "Follow-up"));
        if (form.file() != null && !form.file().isEmpty()) {
            r.setDischargeFileId(files.upload(form.file(), FileService.Kind.DOCUMENT, CurrentUser.id()).getId());
        }
        r.setDischargedAt(Instant.now());
        r.setStatus(FacilityRequest.DISCHARGED);

        tellCareTeam(r, "Patient discharged", r.getPatientName() + " was discharged from " + f.getName()
            + " (" + outcome.replace('_', ' ').toLowerCase(Locale.ROOT) + "). Review the discharge summary.");
        activity.record("REFERRAL_DISCHARGED", "Discharged " + r.getPatientName() + " (" + r.getReference() + ")",
            "FACILITY_REQUEST", r.getId());
        return get(r.getId());
    }

    /* ── pharmacy ── */

    @Transactional
    public Map<String, Object> dispatch(Long id, DispatchBody body) {
        Facility f = ctx.current(FacilityKind.PHARMACY);
        FacilityRequest r = mine(f, id);
        requireStatus(r, FacilityRequest.ACCEPTED, "dispatched");
        Instant at = body == null || body.dispatchedAt() == null ? Instant.now() : body.dispatchedAt();
        notFuture(at, "Dispatch time");
        r.setRider(body == null ? null : blankToNull(body.rider()));
        r.setDispatchedAt(at);
        r.setStatus(FacilityRequest.DISPATCHED);

        tellCareTeam(r, "Drugs on the way", "Order " + r.getReference() + " for " + r.getPatientName()
            + " has left " + f.getName() + (r.getRider() == null ? "" : " with " + r.getRider()) + ".");
        activity.record("ORDER_DISPATCHED", "Dispatched " + r.getReference(), "FACILITY_REQUEST", r.getId());
        return get(r.getId());
    }

    @Transactional
    public Map<String, Object> deliver(Long id, DeliverForm form) {
        Facility f = ctx.current(FacilityKind.PHARMACY);
        FacilityRequest r = mine(f, id);
        if (!FacilityRequest.DISPATCHED.equals(r.getStatus()) && !FacilityRequest.ACCEPTED.equals(r.getStatus())) {
            throw ApiException.conflict("This order is " + r.getStatus().toLowerCase(Locale.ROOT)
                + " and can't be marked delivered.");
        }
        String receivedBy = blankToNull(form.receivedBy());
        if (receivedBy == null) {
            throw ApiException.badRequest("Who received the drugs?");
        }
        Instant at = parseInstant(form.deliveredAt(), "deliveredAt");
        at = at == null ? Instant.now() : at;
        notFuture(at, "Delivery time");
        r.setReceivedBy(limit(receivedBy, 150, "Received by"));
        r.setDeliveryNote(limit(blankToNull(form.note()), 2000, "Note"));
        if (form.proof() != null && !form.proof().isEmpty()) {
            r.setProofFileId(files.upload(form.proof(), FileService.Kind.DOCUMENT, CurrentUser.id()).getId());
        }
        if (r.getDispatchedAt() == null) {
            r.setDispatchedAt(at);
        }
        r.setDeliveredAt(at);
        r.setStatus(FacilityRequest.DELIVERED);

        tellCareTeam(r, "Drugs delivered", "Order " + r.getReference() + " was delivered to " + r.getPatientName()
            + " (received by " + receivedBy + ").");
        activity.record("ORDER_DELIVERED", "Delivered " + r.getReference(), "FACILITY_REQUEST", r.getId());
        return get(r.getId());
    }

    /* ── lab ── */

    @Transactional
    public Map<String, Object> collect(Long id, CollectBody body) {
        Facility f = ctx.current(FacilityKind.LABORATORY);
        FacilityRequest r = mine(f, id);
        requireStatus(r, FacilityRequest.ACCEPTED, "marked collected");
        Instant at = body.collectedAt() == null ? Instant.now() : body.collectedAt();
        notFuture(at, "Collection time");
        r.setSampleId(body.sampleId().trim());
        r.setCollectedBy(body.collectedBy().trim());
        r.setCollectedAt(at);
        r.setPatientFasted(body.patientFasted());
        r.setCollectionNote(blankToNull(body.note()));
        r.setStatus(FacilityRequest.COLLECTED);

        activity.record("SAMPLE_COLLECTED", "Sample " + r.getSampleId() + " collected for " + r.getReference(),
            "FACILITY_REQUEST", r.getId());
        return get(r.getId());
    }

    @Transactional
    public Map<String, Object> submitResults(Long id, ResultsForm form) {
        Facility f = ctx.current(FacilityKind.LABORATORY);
        FacilityRequest r = mine(f, id);
        requireStatus(r, FacilityRequest.COLLECTED, "resulted");
        if (form.report() == null || form.report().isEmpty()) {
            throw ApiException.badRequest("Attach the signed result report.");
        }
        List<Map<String, Object>> sent = readList(form.results());
        if (sent.isEmpty()) {
            throw ApiException.badRequest("Enter a result for every test.");
        }
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> x : sent) {
            byId.put(String.valueOf(x.get("testId")), x);
        }

        List<Map<String, Object>> tests = lines(r);
        boolean anyCritical = false;
        for (Map<String, Object> t : tests) {
            if (Boolean.FALSE.equals(t.get("available"))) {
                continue;
            }
            Map<String, Object> x = byId.get(String.valueOf(t.get("id")));
            String value = x == null ? null : str(x.get("value"));
            if (value == null) {
                throw ApiException.badRequest("Enter a result for " + t.getOrDefault("name", "every test") + ".");
            }
            String flag = str(x.get("flag"));
            flag = flag == null ? "NORMAL" : flag.toUpperCase(Locale.ROOT);
            if (!FLAGS.contains(flag)) {
                throw ApiException.badRequest("Unknown result flag: " + flag);
            }
            anyCritical |= "CRITICAL".equals(flag);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("value", cut(value, 200));
            result.put("unit", cut(str(x.get("unit")), 40));
            result.put("referenceRange", cut(str(x.get("referenceRange")), 100));
            result.put("flag", flag);
            t.put("result", result);
        }
        boolean critical = anyCritical || Boolean.TRUE.equals(form.critical());
        String comment = limit(blankToNull(form.comment()), 4000, "Comment");
        if (critical && comment == null) {
            throw ApiException.badRequest("Add a comment explaining the critical result.");
        }

        r.setReportFileId(files.upload(form.report(), FileService.Kind.DOCUMENT, CurrentUser.id()).getId());
        r.setLines(write(tests));
        r.setCritical(critical);
        r.setResultComment(comment);
        r.setResultedAt(Instant.now());
        r.setStatus(FacilityRequest.RESULTED);

        tellCareTeam(r, critical ? "CRITICAL lab result" : "Lab results ready",
            (critical ? "Critical result for " : "Results are ready for ") + r.getPatientName() + " ("
                + r.getReference() + ") from " + f.getName() + (critical ? ": " + comment : "."));
        if (critical) {
            /* must reach the medical team straight away, not just whoever ordered it */
            notifications.notifyRoles(Role.CLINICAL_LEADERSHIP, "CRITICAL lab result",
                r.getPatientName() + " (" + r.getReference() + ") from " + f.getName() + ": " + comment, "/cno");
        }
        activity.record(critical ? "LAB_RESULT_CRITICAL" : "LAB_RESULTED",
            "Results submitted for " + r.getReference() + (critical ? " (critical)" : ""), "FACILITY_REQUEST", r.getId());
        return get(r.getId());
    }

    /* ── sending work to a facility (used by the care-team portals) ── */

    @Transactional
    public FacilityRequest send(NewRequest n) {
        Facility f = facilities.findById(n.facilityId())
            .filter(x -> x.getDeletedAt() == null && x.isActive())
            .orElseThrow(() -> ApiException.notFound("Facility not found."));
        if (!f.isAccepting()) {
            throw ApiException.conflict(f.getName() + " isn't accepting new requests right now.");
        }
        String name = n.patient() == null ? null : str(n.patient().get("name"));
        if (name == null) {
            throw ApiException.badRequest("Patient name is required.");
        }
        String urgency = n.urgency() == null ? "ROUTINE" : n.urgency().trim().toUpperCase(Locale.ROOT);
        if (!URGENCIES.contains(urgency)) {
            throw ApiException.badRequest("Urgency must be routine, urgent or emergency.");
        }
        FacilityRequest r = new FacilityRequest(f.getId(), f.getKind(), newReference(f.getKind()), cut(name, 150));
        r.setUrgency(urgency);
        r.setPatientId(n.patientId());
        r.setPatientInfo(write(n.patient()));
        r.setReason(n.reason());
        r.setDiagnosis(n.diagnosis());
        r.setPriorCareSummary(n.priorCareSummary());
        r.setVitals(n.vitals() == null ? null : write(n.vitals()));
        r.setMedications(n.medications() == null ? null : write(n.medications()));
        r.setAllergies(n.allergies() == null ? null : write(n.allergies()));
        r.setClinicalNotes(n.clinicalNotes());
        if (f.getKind() != FacilityKind.HOSPITAL) {
            List<Map<String, Object>> lines = new ArrayList<>();
            int i = 1;
            for (Map<String, Object> line : n.lines() == null ? List.<Map<String, Object>>of() : n.lines()) {
                Map<String, Object> copy = new LinkedHashMap<>(line);
                copy.put("id", i++);
                lines.add(copy);
            }
            if (lines.isEmpty()) {
                throw ApiException.badRequest(f.getKind() == FacilityKind.PHARMACY ? "Add at least one drug." : "Add at least one test.");
            }
            r.setLines(write(lines));
        }
        r.setAttachmentFileId(n.attachmentFileId());
        Long me = CurrentUser.idOrNull();
        r.setRequestedById(me);
        if (me != null) {
            users.findById(me).ifPresent(u -> r.setRequestedByName(u.getFullName()));
        }
        FacilityRequest saved = requests.save(r);

        if (f.getAdminUserId() != null) {
            notifications.notify(f.getAdminUserId(), "New " + noun(saved),
                ("EMERGENCY".equals(urgency) || "URGENT".equals(urgency) ? urgency + ": " : "")
                    + "DiGi Health sent " + saved.getReference() + " for " + saved.getPatientName() + ".",
                portalBase(f.getKind()));
        }
        activity.record("FACILITY_REQUEST_SENT", "Sent " + saved.getReference() + " to " + f.getName(),
            "FACILITY_REQUEST", saved.getId());
        return saved;
    }

    /* ── views ── */

    List<Map<String, Object>> views(List<FacilityRequest> list) {
        List<Long> fileIds = list.stream()
            .flatMap(r -> Stream.of(r.getAttachmentFileId(), r.getDischargeFileId(), r.getProofFileId(), r.getReportFileId()))
            .filter(Objects::nonNull).toList();
        Map<Long, String> urls = files.urlsFor(fileIds);
        Set<Long> invoiced = new HashSet<>();
        if (!list.isEmpty()) {
            for (FacilityInvoice i : invoices.findByFacilityIdOrderByCreatedAtDesc(list.get(0).getFacilityId())) {
                if (i.getRequestId() != null && !FacilityInvoice.REJECTED.equals(i.getStatus())) {
                    invoiced.add(i.getRequestId());
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (FacilityRequest r : list) {
            out.add(view(r, urls, invoiced.contains(r.getId())));
        }
        return out;
    }

    private Map<String, Object> view(FacilityRequest r, Map<Long, String> urls, boolean invoiced) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("reference", r.getReference());
        m.put("kind", r.getKind().name());
        m.put("status", r.getStatus());
        m.put("urgency", r.getUrgency());
        m.put("createdAt", r.getCreatedAt());
        Map<String, Object> patient = new LinkedHashMap<>(readMap(r.getPatientInfo()));
        patient.putIfAbsent("name", r.getPatientName());
        m.put("patient", patient);
        m.put("patientName", r.getPatientName());
        put(m, "reason", r.getReason());
        put(m, "diagnosis", r.getDiagnosis());
        put(m, "priorCareSummary", r.getPriorCareSummary());
        put(m, "clinicalNotes", r.getClinicalNotes());
        if (r.getVitals() != null) {
            m.put("vitals", readMap(r.getVitals()));
        }
        if (r.getMedications() != null) {
            m.put("medications", readRaw(r.getMedications()));
        }
        if (r.getAllergies() != null) {
            m.put("allergies", readRaw(r.getAllergies()));
        }
        put(m, FROM_KEY.get(r.getKind()), r.getRequestedByName() == null ? "DiGi Health care team" : r.getRequestedByName());
        put(m, "acceptedAt", r.getAcceptedAt());
        put(m, "acceptNote", r.getAcceptNote());
        put(m, "declinedAt", r.getDeclinedAt());
        put(m, "declineReason", r.getDeclineReason());
        switch (r.getKind()) {
            case HOSPITAL -> {
                put(m, "expectedArrival", r.getExpectedArrival());
                put(m, "ward", r.getWard());
                put(m, "admittedAt", r.getAdmittedAt());
                put(m, "dischargedAt", r.getDischargedAt());
                put(m, "dischargeSummary", r.getDischargeSummary());
                put(m, "dischargeOutcome", r.getDischargeOutcome());
                put(m, "followUp", r.getFollowUp());
                put(m, "referralLetterUrl", urls.get(r.getAttachmentFileId()));
                put(m, "dischargeSummaryUrl", urls.get(r.getDischargeFileId()));
            }
            case PHARMACY -> {
                m.put("items", lines(r));
                put(m, "dispatchAt", r.getDispatchAt());
                put(m, "rider", r.getRider());
                put(m, "dispatchedAt", r.getDispatchedAt());
                put(m, "deliveredAt", r.getDeliveredAt());
                put(m, "receivedBy", r.getReceivedBy());
                put(m, "deliveryNote", r.getDeliveryNote());
                put(m, "prescriptionUrl", urls.get(r.getAttachmentFileId()));
                put(m, "deliveryProofUrl", urls.get(r.getProofFileId()));
            }
            case LABORATORY -> {
                m.put("tests", lines(r));
                put(m, "collectionType", r.getCollectionType());
                put(m, "scheduledAt", r.getScheduledAt());
                put(m, "turnaroundHours", r.getTurnaroundHours());
                put(m, "collectedAt", r.getCollectedAt());
                put(m, "collectedBy", r.getCollectedBy());
                put(m, "sampleId", r.getSampleId());
                put(m, "patientFasted", r.getPatientFasted());
                put(m, "collectionNote", r.getCollectionNote());
                if (r.getCollectedAt() != null && r.getTurnaroundHours() != null) {
                    m.put("resultsDueAt", r.getCollectedAt().plus(r.getTurnaroundHours(), ChronoUnit.HOURS));
                }
                put(m, "resultedAt", r.getResultedAt());
                m.put("critical", r.isCritical());
                put(m, "resultComment", r.getResultComment());
                put(m, "requestFormUrl", urls.get(r.getAttachmentFileId()));
                put(m, "resultReportUrl", urls.get(r.getReportFileId()));
            }
        }
        m.put("invoiced", invoiced);
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    /* ── helpers ── */

    FacilityRequest mine(Facility f, Long id) {
        return requests.findByIdAndFacilityId(id, f.getId())
            .orElseThrow(() -> ApiException.notFound("Request not found."));
    }

    private static void requireStatus(FacilityRequest r, String needed, String verb) {
        if (!needed.equals(r.getStatus())) {
            throw ApiException.conflict("This " + noun(r) + " is " + r.getStatus().toLowerCase(Locale.ROOT)
                + ", so it can't be " + verb + ".");
        }
    }

    private static String noun(FacilityRequest r) {
        return NOUN.get(r.getKind()).toLowerCase(Locale.ROOT);
    }

    static String portalBase(FacilityKind kind) {
        return switch (kind) {
            case HOSPITAL -> "/hospital";
            case PHARMACY -> "/pharmacy";
            case LABORATORY -> "/laboratory";
        };
    }

    /** Applies the facility's available / price choices to every line; at least one must stay available. */
    private List<Map<String, Object>> applyChoices(List<Map<String, Object>> lines, List<LineChoice> choices,
                                                   String priceKey, String what) {
        Map<String, LineChoice> byId = new HashMap<>();
        for (LineChoice c : choices == null ? List.<LineChoice>of() : choices) {
            byId.put(String.valueOf(c.id()), c);
        }
        int available = 0;
        for (Map<String, Object> line : lines) {
            LineChoice c = byId.get(String.valueOf(line.get("id")));
            if (c == null) {
                throw ApiException.badRequest("Confirm every " + what + " on the request.");
            }
            boolean ok = !Boolean.FALSE.equals(c.available());
            line.put("available", ok);
            if (ok) {
                BigDecimal price = c.unitPrice() != null ? c.unitPrice() : c.price();
                if (price == null || price.signum() < 0) {
                    throw ApiException.badRequest("Enter a price for " + line.getOrDefault("name", "each " + what) + ".");
                }
                line.put(priceKey, price);
                available++;
            } else {
                line.remove(priceKey);
            }
        }
        if (available == 0) {
            throw ApiException.badRequest("If nothing on the request is available, decline it instead.");
        }
        return lines;
    }

    private void tellCareTeam(FacilityRequest r, String title, String message) {
        if (r.getRequestedById() != null) {
            notifications.notify(r.getRequestedById(), title, message, null);
        } else {
            notifications.notifyRoles(Role.CLINICAL_LEADERSHIP, title, message, "/cno");
        }
    }

    private static String whenText(FacilityRequest r) {
        if (r.getExpectedArrival() != null) {
            return " — expected " + fmt(r.getExpectedArrival());
        }
        if (r.getDispatchAt() != null) {
            return " — dispatch " + fmt(r.getDispatchAt());
        }
        if (r.getScheduledAt() != null) {
            return " — " + ("HOME".equals(r.getCollectionType()) ? "home collection " : "walk-in ") + fmt(r.getScheduledAt());
        }
        return "";
    }

    private static String fmt(Instant t) {
        return java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, h:mma", Locale.ENGLISH)
            .format(t.atZone(LAGOS));
    }

    private String newReference(FacilityKind kind) {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(PREFIX.get(kind)).append('-');
            for (int i = 0; i < 6; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            if (!requests.existsByReference(sb.toString())) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("Couldn't generate a unique reference");
    }

    List<Map<String, Object>> lines(FacilityRequest r) {
        return r.getLines() == null ? new ArrayList<>() : readList(r.getLines());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> readMap(String text) {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = json.readValue(text, Map.class);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> readList(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Object> raw = json.readValue(text, List.class);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : raw == null ? List.of() : raw) {
                if (o instanceof Map<?, ?> map) {
                    out.add(new LinkedHashMap<>((Map<String, Object>) map));
                } else if (o != null) {
                    /* plain strings, e.g. medications / allergies */
                    Map<String, Object> wrap = new LinkedHashMap<>();
                    wrap.put("name", String.valueOf(o));
                    out.add(wrap);
                }
            }
            return out;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("The list sent wasn't valid JSON.");
        }
    }

    /** Any JSON array as-is (e.g. medications: ["Metformin 500mg"]). */
    private List<Object> readRaw(String text) {
        try {
            List<?> raw = json.readValue(text, List.class);
            return raw == null ? List.of() : new ArrayList<>(raw);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    String write(Object value) {
        return json.writeValueAsString(value);
    }

    private static BigDecimal sum(Stream<FacilityInvoice> s) {
        return s.map(FacilityInvoice::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private static void notFuture(Instant t, String label) {
        if (t.isAfter(Instant.now().plus(10, ChronoUnit.MINUTES))) {
            throw ApiException.badRequest(label + " can't be in the future.");
        }
    }

    static Instant parseInstant(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid date/time for " + field + ".");
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String str(Object o) {
        return o == null ? null : blankToNull(String.valueOf(o));
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    static String limit(String s, int max, String label) {
        if (s != null && s.length() > max) {
            throw ApiException.badRequest(label + " is too long (" + max + " characters max).");
        }
        return s;
    }
}
