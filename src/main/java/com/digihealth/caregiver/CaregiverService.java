package com.digihealth.caregiver;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.carerequest.CareRequest;
import com.digihealth.carerequest.CareRequestRepository;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.notification.NotificationService;
import com.digihealth.provider.Provider;
import com.digihealth.user.Role;
import com.digihealth.visit.Visit;
import com.digihealth.visit.VisitReport;
import com.digihealth.visit.VisitReportRepository;
import com.digihealth.visit.VisitRepository;

/*
 * The caregiver's work: assigned patients, visits, visit reports and closing requests.
 * Everything is limited to requests assigned to the signed-in provider.
 */
@Service
public class CaregiverService {

    private static final Set<String> ACTIVITIES = Set.of("VITALS_CHECK", "MEDICATION", "WOUND_CARE", "MOBILITY",
        "HYGIENE", "FEEDING", "SAMPLE_COLLECTION", "NEWBORN_CARE", "EDUCATION");
    private static final Set<String> CONDITIONS = Set.of("IMPROVING", "STABLE", "DETERIORATING");
    private static final Set<Role> SUPERVISORS = Set.of(Role.CNO, Role.CNO_MEDICAL_DIRECTOR, Role.NURSING_SUPERVISOR,
        Role.HEAD_OF_CLINICAL_OPERATIONS, Role.CHIEF_MEDICAL_OFFICER);

    private final CaregiverSupport support;
    private final CareRequestRepository requests;
    private final VisitRepository visits;
    private final VisitReportRepository reports;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final CaregiverEarningsService earnings;

    public CaregiverService(CaregiverSupport support, CareRequestRepository requests, VisitRepository visits,
                            VisitReportRepository reports, NotificationService notifications,
                            ActivityService activity, CaregiverEarningsService earnings) {
        this.support = support;
        this.requests = requests;
        this.visits = visits;
        this.reports = reports;
        this.notifications = notifications;
        this.activity = activity;
        this.earnings = earnings;
    }

    /* ── DTOs ── */

    public record PatientRow(Long id, Long careRequestId, Long patientId, String fullName, String phoneNumber,
                             String email, String address, String locationArea, String serviceNeeded,
                             String description, String preferredContactTime, String status, String requestStatus,
                             boolean isNew, Instant assignedAt, String adminNote, Instant nextVisitAt,
                             String nextVisit, Instant lastVisitAt, String closureNote, String closureReviewNote) {
    }

    public record VisitRow(Long id, Long careRequestId, Long patientId, String patientName, String title,
                           String serviceType, String visitDate, String scheduledTime, Instant scheduledAt,
                           Integer durationMins, String status, String address, String locationArea, String notes,
                           boolean hasReport, Instant checkInAt, Instant checkOutAt) {
    }

    public record ScheduleVisitBody(
            @NotNull(message = "Choose a patient") Long careRequestId,
            @NotBlank(message = "Pick a date") String visitDate,
            @NotBlank(message = "Pick a time") @Pattern(regexp = "^\\d{1,2}:\\d{2}(:\\d{2})?$", message = "Use a time like 09:30") String scheduledTime,
            @Min(value = 15, message = "At least 15 minutes") @Max(value = 720, message = "At most 12 hours") Integer durationMins,
            @Size(max = 150) String title,
            @Size(max = 2000) String notes) {
    }

    public record StatusBody(@NotBlank(message = "Choose a status") String status) {
    }

    public record CloseBody(@NotBlank(message = "Write a closing report")
                            @Size(min = 10, max = 4000, message = "Write at least 10 characters") String closureNote) {
    }

    public record ReportBody(
            @NotNull(message = "Choose a patient") Long careRequestId,
            Long visitId,
            @NotBlank(message = "Pick the visit date") String visitDate,
            @NotEmpty(message = "Tick at least one activity") List<String> activities,
            @NotBlank(message = "Choose the patient's condition") String patientCondition,
            @Size(max = 2000) String medicationGiven,
            @NotBlank(message = "Describe what you observed") @Size(min = 10, max = 6000, message = "Describe what you observed (at least 10 characters)") String notes,
            @Size(max = 2000) String nextSteps,
            @Pattern(regexp = "^\\d{2,3}/\\d{2,3}$", message = "Use the format 120/80") String bloodPressure,
            @Min(20) @Max(250) Integer pulse,
            @jakarta.validation.constraints.DecimalMin("30.0") @jakarta.validation.constraints.DecimalMax("45.0") BigDecimal temperature,
            @Min(50) @Max(100) Integer spo2,
            @jakarta.validation.constraints.DecimalMin("0.5") @jakarta.validation.constraints.DecimalMax("50.0") BigDecimal bloodSugar,
            @Min(4) @Max(80) Integer respiratoryRate) {
    }

    public record ReportRow(Long id, Long careRequestId, Long visitId, String patientName, String serviceType,
                            LocalDate visitDate, List<String> activities, String patientCondition,
                            String medicationGiven, String notes, String nextSteps, String bloodPressure,
                            Integer pulse, BigDecimal temperature, Integer spo2, BigDecimal bloodSugar,
                            Integer respiratoryRate, String status, String reviewNote, Instant createdAt) {
    }

    public record Dashboard(long visitsToday, long activePatients, long totalVisits, long pendingReports,
                            BigDecimal monthEarnings, boolean isAvailable) {
    }

    /* ── Patients (= care requests assigned to me) ── */

    @Transactional(readOnly = true)
    public List<PatientRow> patients() {
        Provider me = support.me();
        List<CareRequest> list = requests.findByAssignedProviderIdOrderByCreatedAtDesc(me.getId());
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(CareRequest::getId).toList();
        Map<Long, Visit> next = firstPerRequest(
            visits.findByCareRequestIdInAndStatusAndScheduledAtGreaterThanEqualOrderByScheduledAtAsc(ids, Visit.SCHEDULED, Instant.now()));
        Map<Long, Visit> last = firstPerRequest(
            visits.findByCareRequestIdInAndStatusOrderByScheduledAtDesc(ids, Visit.COMPLETED));

        return list.stream().map(r -> {
            Visit n = next.get(r.getId());
            Visit l = last.get(r.getId());
            boolean fresh = CareRequest.ASSIGNED.equals(r.getStatus()) && n == null && l == null;
            String status = CareRequest.ASSIGNED.equals(r.getStatus()) ? (fresh ? "ASSIGNED" : "ACTIVE") : r.getStatus();
            return new PatientRow(r.getId(), r.getId(), r.getPatientId(), r.getFullName(), r.getPhoneNumber(), r.getEmail(),
                r.getAddress(), r.getLocationArea(), r.getServiceNeeded(), r.getDescription(), r.getPreferredContactTime(),
                status, r.getStatus(), fresh, r.getAssignedAt(), r.getAdminNote(),
                n == null ? null : n.getScheduledAt(), n == null ? null : CaregiverSupport.friendly(n.getScheduledAt()),
                l == null ? null : l.getScheduledAt(), r.getClosureNote(), r.getClosureReviewNote());
        }).toList();
    }

    /* ── Visits ── */

    @Transactional(readOnly = true)
    public List<VisitRow> visits(String fromText, String toText) {
        Provider me = support.me();
        LocalDate from = AppTime.parseDate(fromText, "from");
        LocalDate to = AppTime.parseDate(toText, "to");
        LocalDate today = AppTime.today();
        if (from == null) {
            from = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        }
        if (to == null) {
            to = from.plusDays(6);
        }
        if (to.isBefore(from) || to.isAfter(from.plusDays(92))) {
            throw ApiException.badRequest("Choose a date range of up to 3 months.");
        }
        return rows(visits.findByProviderIdAndScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
            me.getId(), AppTime.startOf(from), AppTime.endOf(to)));
    }

    @Transactional(readOnly = true)
    public List<VisitRow> today() {
        Provider me = support.me();
        LocalDate today = AppTime.today();
        return rows(visits.findByProviderIdAndScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
            me.getId(), AppTime.startOf(today), AppTime.endOf(today)));
    }

    @Transactional
    public VisitRow schedule(ScheduleVisitBody body) {
        Provider me = support.me();
        CareRequest r = myOpenRequest(me, body.careRequestId());
        LocalDate date = AppTime.parseDate(body.visitDate(), "visitDate");
        LocalTime time;
        try {
            time = LocalTime.parse(body.scheduledTime().length() == 4 ? "0" + body.scheduledTime() : body.scheduledTime());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Use a time like 09:30.");
        }
        Instant at = date.atTime(time).atZone(AppTime.ZONE).toInstant();
        if (at.isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
            throw ApiException.badRequest("Pick a time in the future.");
        }
        int minutes = body.durationMins() == null ? 60 : body.durationMins();
        Instant end = at.plus(Duration.ofMinutes(minutes));

        /* No double-booking. */
        for (Visit v : visits.findByProviderIdAndScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
                me.getId(), AppTime.startOf(date), AppTime.endOf(date))) {
            if (Visit.CANCELLED.equals(v.getStatus())) {
                continue;
            }
            Instant vEnd = v.getScheduledAt().plus(Duration.ofMinutes(v.getDurationMinutes() == null ? 60 : v.getDurationMinutes()));
            if (at.isBefore(vEnd) && v.getScheduledAt().isBefore(end)) {
                throw ApiException.conflict("You already have a visit at " + CaregiverSupport.time(v.getScheduledAt())
                    + " that day. Choose another time.");
            }
        }

        Visit v = new Visit(me.getId(), at);
        v.setCareRequestId(r.getId());
        v.setPatientId(r.getPatientId());
        v.setServiceType(r.getServiceNeeded());
        v.setTitle(CaregiverSupport.blankToNull(body.title()));
        v.setDurationMinutes(minutes);
        v.setAddress(r.getAddress());
        v.setLocationArea(r.getLocationArea());
        v.setNotes(CaregiverSupport.blankToNull(body.notes()));
        v.setCreatedById(CurrentUser.idOrNull());
        v = visits.save(v);

        if (r.getPatientId() != null) {
            notifications.notify(r.getPatientId(), "Visit scheduled",
                me.getFullName() + " will visit on " + CaregiverSupport.friendly(at) + ".", "/patient/bookings");
        }
        activity.record("VISIT_SCHEDULED", "Scheduled a visit with " + r.getFullName() + " for "
            + CaregiverSupport.friendly(at), "VISIT", v.getId());
        return rows(List.of(v)).get(0);
    }

    @Transactional
    public VisitRow updateStatus(Long id, String statusText) {
        Provider me = support.me();
        Visit v = visits.findById(id).filter(x -> me.getId().equals(x.getProviderId()))
            .orElseThrow(() -> ApiException.notFound("Visit not found."));
        String next = statusText.trim().toUpperCase();
        String current = v.getStatus();
        Instant now = Instant.now();
        CareRequest r = v.getCareRequestId() == null ? null : requests.findById(v.getCareRequestId()).orElse(null);
        String patient = r == null ? "patient" : r.getFullName();

        switch (next) {
            case Visit.IN_PROGRESS -> {
                require(Visit.SCHEDULED.equals(current), "Only a scheduled visit can be started.");
                if (!AppTime.today().equals(LocalDate.ofInstant(v.getScheduledAt(), AppTime.ZONE))) {
                    throw ApiException.badRequest("You can only start a visit on the day it's scheduled.");
                }
                v.setStatus(Visit.IN_PROGRESS);
                v.setCheckInAt(now);
                if (v.getPatientId() != null) {
                    notifications.notify(v.getPatientId(), "Your caregiver has arrived",
                        me.getFullName() + " has started your visit.", "/patient/bookings");
                }
            }
            case Visit.COMPLETED -> {
                require(Visit.IN_PROGRESS.equals(current), "Start the visit before completing it.");
                v.setStatus(Visit.COMPLETED);
                v.setCheckOutAt(now);
            }
            case Visit.CANCELLED -> {
                require(Visit.SCHEDULED.equals(current), "Only a scheduled visit can be cancelled.");
                v.setStatus(Visit.CANCELLED);
                if (v.getPatientId() != null) {
                    notifications.notify(v.getPatientId(), "Visit cancelled",
                        "Your visit on " + CaregiverSupport.friendly(v.getScheduledAt())
                            + " was cancelled. Your caregiver will arrange a new time.", "/patient/bookings");
                }
            }
            default -> throw ApiException.badRequest("Status must be IN_PROGRESS, COMPLETED or CANCELLED.");
        }
        activity.record("VISIT_" + next, label(next) + " visit with " + patient, "VISIT", v.getId());
        return rows(List.of(v)).get(0);
    }

    /* ── Close a request (supervisor approves) ── */

    @Transactional
    public PatientRow close(Long requestId, String note) {
        Provider me = support.me();
        CareRequest r = myOpenRequest(me, requestId);
        if (!visits.findByProviderIdAndStatusIn(me.getId(), List.of(Visit.IN_PROGRESS)).stream()
                .filter(v -> r.getId().equals(v.getCareRequestId())).toList().isEmpty()) {
            throw ApiException.badRequest("Finish the visit in progress before closing this request.");
        }
        r.setStatus(CareRequest.PENDING_CLOSURE);
        r.setClosureNote(note.trim());
        r.setClosureRequestedAt(Instant.now());
        r.setClosureReviewNote(null);

        String msg = me.getFullName() + " closed " + r.getFullName() + "'s " + r.getServiceNeeded() + " request.";
        notifications.notifyRoles(Role.ADMINS, "Request awaiting closure", msg, "/admin/care-requests");
        notifications.notifyRoles(SUPERVISORS, "Request awaiting closure", msg, "/cno/requests");
        activity.record("CARE_REQUEST_CLOSURE_REQUESTED", msg, "CARE_REQUEST", r.getId());
        return patients().stream().filter(p -> p.id().equals(r.getId())).findFirst().orElseThrow();
    }

    /* ── Visit reports ── */

    @Transactional(readOnly = true)
    public List<ReportRow> reports() {
        Provider me = support.me();
        List<VisitReport> list = reports.findByProviderIdOrderByVisitDateDescCreatedAtDesc(me.getId());
        Map<Long, CareRequest> reqs = requestsById(list.stream().map(VisitReport::getCareRequestId).toList());
        return list.stream().map(r -> reportRow(r, reqs.get(r.getCareRequestId()))).toList();
    }

    @Transactional
    public ReportRow createReport(ReportBody body) {
        Provider me = support.me();
        CareRequest r = requests.findById(body.careRequestId())
            .filter(x -> me.getId().equals(x.getAssignedProviderId()))
            .orElseThrow(() -> ApiException.badRequest("Choose one of your patients."));
        LocalDate date = AppTime.parseDate(body.visitDate(), "visitDate");
        if (date.isAfter(AppTime.today())) {
            throw ApiException.badRequest("Visit date can't be in the future.");
        }
        List<String> acts = body.activities().stream().map(a -> a.trim().toUpperCase()).distinct().toList();
        if (!ACTIVITIES.containsAll(acts)) {
            throw ApiException.badRequest("One of the activities isn't recognised.");
        }
        String condition = body.patientCondition().trim().toUpperCase();
        if (!CONDITIONS.contains(condition)) {
            throw ApiException.badRequest("Condition must be improving, stable or deteriorating.");
        }
        if (body.visitId() != null) {
            Visit v = visits.findById(body.visitId())
                .filter(x -> me.getId().equals(x.getProviderId()) && r.getId().equals(x.getCareRequestId()))
                .orElseThrow(() -> ApiException.badRequest("That visit isn't one of yours for this patient."));
            if (reports.existsByVisitId(v.getId())) {
                throw ApiException.conflict("A report for this visit has already been submitted.");
            }
        }

        VisitReport rep = new VisitReport(r.getId(), me.getId(), date);
        rep.setVisitId(body.visitId());
        rep.setPatientId(r.getPatientId());
        rep.setActivities(String.join(",", acts));
        rep.setPatientCondition(condition);
        rep.setMedicationGiven(CaregiverSupport.blankToNull(body.medicationGiven()));
        rep.setNotes(body.notes().trim());
        rep.setNextSteps(CaregiverSupport.blankToNull(body.nextSteps()));
        rep.setBloodPressure(CaregiverSupport.blankToNull(body.bloodPressure()));
        rep.setPulse(body.pulse());
        rep.setTemperature(body.temperature());
        rep.setSpo2(body.spo2());
        rep.setBloodSugar(body.bloodSugar());
        rep.setRespiratoryRate(body.respiratoryRate());
        rep = reports.save(rep);

        boolean urgent = "DETERIORATING".equals(condition);
        notifications.notifyRoles(SUPERVISORS, urgent ? "Patient deteriorating" : "New visit report",
            me.getFullName() + " reported on " + r.getFullName()
                + (urgent ? ". Condition: deteriorating - please review now." : "."), "/cno/visit-reports");
        activity.record("VISIT_REPORT_SUBMITTED", "Reported on " + r.getFullName() + " (" + condition.toLowerCase() + ")",
            "VISIT_REPORT", rep.getId());
        return reportRow(rep, r);
    }

    /* ── Dashboard ── */

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        Provider me = support.me();
        LocalDate today = AppTime.today();
        long visitsToday = visits.findByProviderIdAndScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
                me.getId(), AppTime.startOf(today), AppTime.endOf(today)).stream()
            .filter(v -> !Visit.CANCELLED.equals(v.getStatus())).count();
        long active = requests.countByAssignedProviderIdAndStatusIn(me.getId(),
            List.of(CareRequest.ASSIGNED, CareRequest.PENDING_CLOSURE));
        long total = visits.countByProviderIdAndStatus(me.getId(), Visit.COMPLETED);
        long pendingReports = reports.findByProviderIdOrderByVisitDateDescCreatedAtDesc(me.getId()).stream()
            .filter(r -> VisitReport.SUBMITTED.equals(r.getStatus())).count();
        return new Dashboard(visitsToday, active, total, pendingReports, earnings.monthTotal(me), me.isAvailable());
    }

    /* ── helpers ── */

    private CareRequest myOpenRequest(Provider me, Long requestId) {
        CareRequest r = requests.findById(requestId)
            .filter(x -> me.getId().equals(x.getAssignedProviderId()))
            .orElseThrow(() -> ApiException.notFound("That request isn't assigned to you."));
        if (!CareRequest.ASSIGNED.equals(r.getStatus())) {
            throw ApiException.conflict("This request is " + label(r.getStatus()) + ", so it can't be changed.");
        }
        return r;
    }

    private List<VisitRow> rows(List<Visit> list) {
        Map<Long, CareRequest> reqs = requestsById(list.stream().map(Visit::getCareRequestId).toList());
        Set<Long> reported = reports.findByVisitIdIn(list.stream().map(Visit::getId).toList()).stream()
            .map(VisitReport::getVisitId).collect(Collectors.toSet());
        return list.stream().map(v -> {
            CareRequest r = v.getCareRequestId() == null ? null : reqs.get(v.getCareRequestId());
            return new VisitRow(v.getId(), v.getCareRequestId(), v.getPatientId(), r == null ? null : r.getFullName(),
                v.getTitle(), v.getServiceType(), CaregiverSupport.day(v.getScheduledAt()),
                CaregiverSupport.time(v.getScheduledAt()), v.getScheduledAt(), v.getDurationMinutes(), v.getStatus(),
                v.getAddress(), v.getLocationArea(), v.getNotes(), reported.contains(v.getId()),
                v.getCheckInAt(), v.getCheckOutAt());
        }).toList();
    }

    private Map<Long, CareRequest> requestsById(Collection<Long> ids) {
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        return wanted.isEmpty() ? new HashMap<>()
            : requests.findAllById(wanted).stream().collect(Collectors.toMap(CareRequest::getId, Function.identity()));
    }

    private static Map<Long, Visit> firstPerRequest(List<Visit> ordered) {
        Map<Long, Visit> out = new LinkedHashMap<>();
        for (Visit v : ordered) {
            out.putIfAbsent(v.getCareRequestId(), v);
        }
        return out;
    }

    private static ReportRow reportRow(VisitReport r, CareRequest req) {
        return new ReportRow(r.getId(), r.getCareRequestId(), r.getVisitId(), req == null ? null : req.getFullName(),
            req == null ? null : req.getServiceNeeded(), r.getVisitDate(), r.activityList(), r.getPatientCondition(),
            r.getMedicationGiven(), r.getNotes(), r.getNextSteps(), r.getBloodPressure(), r.getPulse(),
            r.getTemperature(), r.getSpo2(), r.getBloodSugar(), r.getRespiratoryRate(), r.getStatus(),
            r.getReviewNote(), r.getCreatedAt());
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw ApiException.conflict(message);
        }
    }

    private static String label(String status) {
        String s = status == null ? "" : status.toLowerCase().replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
