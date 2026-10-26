package com.digihealth.cno;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.activity.ActivityService.ActivityView;
import com.digihealth.admin.AdminUserService;
import com.digihealth.carerequest.CareRequest;
import com.digihealth.carerequest.CareRequestDtos;
import com.digihealth.carerequest.CareRequestRepository;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.notification.NotificationService;
import com.digihealth.provider.Provider;
import com.digihealth.provider.ProviderRepository;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;
import com.digihealth.visit.Visit;
import com.digihealth.visit.VisitReport;
import com.digihealth.visit.VisitReportRepository;
import com.digihealth.visit.VisitRepository;

/*
 * Clinical leadership: the team, visit-report review, performance and exports.
 * Approving a visit report makes its vitals and summary visible to the patient.
 */
@Service
public class CnoService {

    private static final int DEFAULT_CAPACITY = 10;
    private static final Set<String> OPEN = Set.of(CareRequest.ASSIGNED, CareRequest.PENDING_CLOSURE);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm").withZone(AppTime.ZONE);

    private final ProviderRepository providers;
    private final UserRepository users;
    private final CareRequestRepository requests;
    private final VisitRepository visits;
    private final VisitReportRepository reports;
    private final AdminUserService userService;
    private final NotificationService notifications;
    private final ActivityService activity;

    public CnoService(ProviderRepository providers, UserRepository users, CareRequestRepository requests,
                      VisitRepository visits, VisitReportRepository reports, AdminUserService userService,
                      NotificationService notifications, ActivityService activity) {
        this.providers = providers;
        this.users = users;
        this.requests = requests;
        this.visits = visits;
        this.reports = reports;
        this.userService = userService;
        this.notifications = notifications;
        this.activity = activity;
    }

    /* ── DTOs ── */

    public record Stats(long activeNurses, long visitsToday, long activePatients, BigDecimal avgRating,
                        Integer completionRate, long urgentRequests, long reportsToReview, long pendingClosures) {
    }

    public record TeamMember(Long id, Long userId, String fullName, String email, String phoneNumber,
                             String serviceProviderType, String locationArea, List<String> coverageAreas,
                             boolean available, boolean active, long activePatients, int capacity, BigDecimal rating,
                             String status, Instant approvedAt) {
    }

    public record DailyCount(String date, long scheduled, long completed) {
    }

    public record NewProvider(
            @NotBlank(message = "Full name is required") @Size(max = 150) String fullName,
            @NotBlank(message = "Enter a valid email") @Email(message = "Enter a valid email") String email,
            @NotBlank(message = "Phone is required") @Pattern(regexp = CareRequestDtos.PHONE_RULE, message = "Enter a valid phone number") String phoneNumber,
            @NotBlank(message = "Choose the provider type") @Size(max = 40) String serviceProviderType,
            @NotBlank(message = "Choose their main area") @Size(max = 60) String locationArea,
            String role) {
    }

    public record ReportRow(Long id, Long careRequestId, Long visitId, Long providerId, String providerName,
                            String patientName, String serviceType, LocalDate visitDate, List<String> activities,
                            String patientCondition, String medicationGiven, String notes, String nextSteps,
                            String bloodPressure, Integer pulse, BigDecimal temperature, Integer spo2,
                            BigDecimal bloodSugar, Integer respiratoryRate, String status, String reviewNote,
                            Instant reviewedAt, Instant createdAt) {
    }

    public record PerformanceRow(Long id, String fullName, String serviceProviderType, long visitsScheduled,
                                 long visitsCompleted, Integer completionRate, BigDecimal avgRating,
                                 long reportsSubmitted, Integer onTimeReportRate, long activePatients) {
    }

    public record VisitRow(Long id, Long careRequestId, String patientName, String providerName, String serviceType,
                           String visitDate, String scheduledTime, Instant scheduledAt, String status,
                           String location, String locationArea, Integer rating) {
    }

    /* ── Dashboard ── */

    @Transactional(readOnly = true)
    public Stats stats() {
        LocalDate today = AppTime.today();
        List<Provider> team = approvedTeam();
        long activeNurses = team.stream().filter(Provider::isAvailable).count();
        long visitsToday = visits.findByScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
                AppTime.startOf(today), AppTime.endOf(today)).stream()
            .filter(v -> !Visit.CANCELLED.equals(v.getStatus())).count();
        long activePatients = requests.findByStatusIn(OPEN).stream()
            .map(r -> r.getPatientId() != null ? r.getPatientId() : -r.getId()).distinct().count();

        OptionalDouble avg = visits.findByRatingIsNotNull().stream().mapToInt(v -> v.getRating()).average();
        List<Visit> month = visits.findByScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
            AppTime.startOf(today.withDayOfMonth(1)), AppTime.endOf(today));
        long due = month.stream().filter(v -> !Visit.CANCELLED.equals(v.getStatus())
            && v.getScheduledAt().isBefore(Instant.now())).count();
        long done = month.stream().filter(v -> Visit.COMPLETED.equals(v.getStatus())).count();

        long staleRequests = requests.countByStatusAndCreatedAtLessThan(CareRequest.PENDING, Instant.now().minus(Duration.ofHours(24)));
        List<VisitReport> waiting = reports.findByStatusOrderByCreatedAtDesc(VisitReport.SUBMITTED);
        long deteriorating = waiting.stream().filter(r -> "DETERIORATING".equals(r.getPatientCondition())).count();

        return new Stats(activeNurses, visitsToday, activePatients,
            avg.isPresent() ? BigDecimal.valueOf(avg.getAsDouble()).setScale(1, RoundingMode.HALF_UP) : null,
            due == 0 ? null : (int) Math.round(done * 100.0 / due), staleRequests + deteriorating, waiting.size(),
            requests.countByStatus(CareRequest.PENDING_CLOSURE));
    }

    @Transactional(readOnly = true)
    public List<ActivityView> activity(int limit) {
        return activity.recent(limit);
    }

    @Transactional(readOnly = true)
    public List<DailyCount> daily(String fromText, String toText) {
        LocalDate to = Objects.requireNonNullElse(AppTime.parseDate(toText, "to"), AppTime.today());
        LocalDate from = Objects.requireNonNullElse(AppTime.parseDate(fromText, "from"), to.withDayOfMonth(1));
        checkRange(from, to);
        Map<LocalDate, long[]> counts = new LinkedHashMap<>();
        from.datesUntil(to.plusDays(1)).forEach(d -> counts.put(d, new long[2]));
        for (Visit v : visits.findByScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
                AppTime.startOf(from), AppTime.endOf(to))) {
            if (Visit.CANCELLED.equals(v.getStatus())) {
                continue;
            }
            long[] c = counts.get(LocalDate.ofInstant(v.getScheduledAt(), AppTime.ZONE));
            if (c != null) {
                c[0]++;
                if (Visit.COMPLETED.equals(v.getStatus())) {
                    c[1]++;
                }
            }
        }
        return counts.entrySet().stream().map(e -> new DailyCount(e.getKey().toString(), e.getValue()[0], e.getValue()[1])).toList();
    }

    /* ── Team ── */

    @Transactional(readOnly = true)
    public List<TeamMember> team(String status) {
        String st = status == null || status.isBlank() ? Provider.APPROVED : status.trim().toUpperCase();
        List<Provider> list = providers.findByStatusOrderByCreatedAtDesc(st);
        Map<Long, Boolean> active = users.findAllById(list.stream().map(Provider::getUserId).filter(Objects::nonNull).toList())
            .stream().collect(Collectors.toMap(User::getId, User::isActive));
        Map<Long, Long> load = openLoad(list.stream().map(Provider::getId).toList());
        Map<Long, BigDecimal> ratings = ratingsByProvider(visits.findByRatingIsNotNull());
        return list.stream().map(p -> new TeamMember(p.getId(), p.getUserId(), p.getFullName(), p.getEmail(),
            p.getPhoneNumber(), type(p), p.getLocationArea(), List.copyOf(p.areasCovered()), p.isAvailable(),
            Boolean.TRUE.equals(active.get(p.getUserId())), load.getOrDefault(p.getId(), 0L), DEFAULT_CAPACITY,
            ratings.get(p.getId()), p.getStatus(), p.getReviewedAt())).toList();
    }

    /** Supervisors add vetted staff directly: approved straight away, invite emailed. */
    @Transactional
    public AdminUserService.UserView createProvider(NewProvider body) {
        String role = body.role() == null || body.role().isBlank() ? Role.SERVICE_PROVIDER.name() : body.role().trim().toUpperCase();
        if (!Role.SERVICE_PROVIDER.name().equals(role) && !Role.CLINICAL_PERSONNEL.name().equals(role)) {
            throw ApiException.badRequest("Supervisors can only add care providers.");
        }
        return userService.create(new AdminUserService.CreateUser(body.fullName(), body.email(), body.phoneNumber(), role,
            body.locationArea(), body.serviceProviderType(), null));
    }

    /* ── Visit reports ── */

    @Transactional(readOnly = true)
    public List<ReportRow> reports(String status, String fromText, String toText) {
        LocalDate from = AppTime.parseDate(fromText, "from");
        LocalDate to = AppTime.parseDate(toText, "to");
        List<VisitReport> list = from != null || to != null
            ? reports.findByVisitDateBetweenOrderByVisitDateDesc(
                from == null ? LocalDate.of(2000, 1, 1) : from, to == null ? AppTime.today() : to)
            : reports.findAllByOrderByCreatedAtDesc();
        String wanted = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status) ? null
            : "PENDING_REVIEW".equalsIgnoreCase(status) || "PENDING".equalsIgnoreCase(status) ? VisitReport.SUBMITTED
            : status.trim().toUpperCase();
        if (wanted != null) {
            list = list.stream().filter(r -> wanted.equals(r.getStatus())).toList();
        }
        return rows(list);
    }

    @Transactional
    public ReportRow approve(Long id, String note) {
        VisitReport r = pendingReport(id);
        r.setStatus(VisitReport.APPROVED);
        r.setReviewNote(blankToNull(note));
        r.setReviewedById(CurrentUser.idOrNull());
        r.setReviewedAt(Instant.now());

        providerUser(r.getProviderId()).ifPresent(uid -> notifications.notify(uid, "Visit report approved",
            "Your report for " + r.getVisitDate() + " was approved.", "/caregiver/reports"));
        if (r.getPatientId() != null) {
            notifications.notify(r.getPatientId(), "Your visit report is ready",
                "The report from your visit on " + r.getVisitDate() + " is now available.", "/patient/care-reports");
        }
        activity.record("VISIT_REPORT_APPROVED", "Approved the visit report for " + r.getVisitDate(), "VISIT_REPORT", r.getId());
        return rows(List.of(r)).get(0);
    }

    @Transactional
    public ReportRow returnReport(Long id, String reason) {
        VisitReport r = pendingReport(id);
        String why = blankToNull(reason);
        if (why == null) {
            throw ApiException.badRequest("Tell the provider what to fix.");
        }
        r.setStatus(VisitReport.RETURNED);
        r.setReviewNote(why);
        r.setReviewedById(CurrentUser.idOrNull());
        r.setReviewedAt(Instant.now());

        providerUser(r.getProviderId()).ifPresent(uid -> notifications.notify(uid, "Visit report returned",
            "Your report for " + r.getVisitDate() + " needs changes: " + why + " Please submit a corrected report.",
            "/caregiver/reports"));
        activity.record("VISIT_REPORT_RETURNED", "Returned the visit report for " + r.getVisitDate() + ": " + why,
            "VISIT_REPORT", r.getId());
        return rows(List.of(r)).get(0);
    }

    /* ── Performance ── */

    @Transactional(readOnly = true)
    public List<PerformanceRow> performance(String monthText) {
        YearMonth month;
        try {
            month = monthText == null || monthText.isBlank() ? YearMonth.now(AppTime.ZONE) : YearMonth.parse(monthText.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Month must look like 2026-10.");
        }
        Instant start = AppTime.startOf(month.atDay(1));
        Instant end = AppTime.endOf(month.atEndOfMonth());
        List<Visit> monthVisits = visits.findByScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(start, end);
        List<VisitReport> monthReports = reports.findByVisitDateBetweenOrderByVisitDateDesc(month.atDay(1), month.atEndOfMonth());
        Map<Long, Visit> visitById = new HashMap<>();
        monthVisits.forEach(v -> visitById.put(v.getId(), v));
        List<Provider> team = providers.findByStatusOrderByCreatedAtDesc(Provider.APPROVED);
        Map<Long, Long> load = openLoad(team.stream().map(Provider::getId).toList());

        List<PerformanceRow> out = new ArrayList<>();
        for (Provider p : team) {
            List<Visit> mine = monthVisits.stream().filter(v -> p.getId().equals(v.getProviderId())).toList();
            long scheduled = mine.stream().filter(v -> !Visit.CANCELLED.equals(v.getStatus())).count();
            long completed = mine.stream().filter(v -> Visit.COMPLETED.equals(v.getStatus())).count();
            OptionalDouble rating = mine.stream().filter(v -> v.getRating() != null).mapToInt(v -> v.getRating()).average();
            List<VisitReport> myReports = monthReports.stream().filter(r -> p.getId().equals(r.getProviderId())).toList();
            List<VisitReport> linked = myReports.stream().filter(r -> r.getVisitId() != null && visitById.containsKey(r.getVisitId())
                && visitById.get(r.getVisitId()).getCheckOutAt() != null).toList();
            long onTime = linked.stream().filter(r -> !r.getCreatedAt()
                .isAfter(visitById.get(r.getVisitId()).getCheckOutAt().plus(Duration.ofHours(24)))).count();
            out.add(new PerformanceRow(p.getId(), p.getFullName(), type(p), scheduled, completed,
                scheduled == 0 ? null : (int) Math.round(completed * 100.0 / scheduled),
                rating.isPresent() ? BigDecimal.valueOf(rating.getAsDouble()).setScale(1, RoundingMode.HALF_UP) : null,
                myReports.size(), linked.isEmpty() ? null : (int) Math.round(onTime * 100.0 / linked.size()),
                load.getOrDefault(p.getId(), 0L)));
        }
        return out;
    }

    /* ── Visits for export ── */

    @Transactional(readOnly = true)
    public List<VisitRow> visits(String fromText, String toText) {
        LocalDate to = Objects.requireNonNullElse(AppTime.parseDate(toText, "to"), AppTime.today());
        LocalDate from = Objects.requireNonNullElse(AppTime.parseDate(fromText, "from"), to.withDayOfMonth(1));
        checkRange(from, to);
        List<Visit> list = visits.findByScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
            AppTime.startOf(from), AppTime.endOf(to));
        Map<Long, Provider> provs = byId(providers.findAllById(ids(list.stream().map(Visit::getProviderId).toList())), Provider::getId);
        Map<Long, CareRequest> reqs = byId(requests.findAllById(ids(list.stream().map(Visit::getCareRequestId).toList())), CareRequest::getId);
        return list.stream().map(v -> {
            CareRequest r = v.getCareRequestId() == null ? null : reqs.get(v.getCareRequestId());
            Provider p = provs.get(v.getProviderId());
            return new VisitRow(v.getId(), v.getCareRequestId(), r == null ? null : r.getFullName(),
                p == null ? null : p.getFullName(), v.getServiceType(),
                LocalDate.ofInstant(v.getScheduledAt(), AppTime.ZONE).toString(), HH_MM.format(v.getScheduledAt()),
                v.getScheduledAt(), v.getStatus(), v.getAddress(), v.getLocationArea(),
                v.getRating() == null ? null : v.getRating().intValue());
        }).toList();
    }

    /* ── helpers ── */

    private List<Provider> approvedTeam() {
        List<Provider> list = providers.findByStatusOrderByCreatedAtDesc(Provider.APPROVED);
        Set<Long> active = users.findAllById(list.stream().map(Provider::getUserId).filter(Objects::nonNull).toList())
            .stream().filter(User::isActive).map(User::getId).collect(Collectors.toSet());
        return list.stream().filter(p -> active.contains(p.getUserId())).toList();
    }

    private Map<Long, Long> openLoad(List<Long> providerIds) {
        Map<Long, Long> load = new HashMap<>();
        if (!providerIds.isEmpty()) {
            for (Object[] row : requests.countOpenByProvider(providerIds, OPEN)) {
                load.put((Long) row[0], (Long) row[1]);
            }
        }
        return load;
    }

    private static Map<Long, BigDecimal> ratingsByProvider(List<Visit> rated) {
        return rated.stream().collect(Collectors.groupingBy(Visit::getProviderId,
            Collectors.collectingAndThen(Collectors.averagingInt(v -> v.getRating()),
                d -> BigDecimal.valueOf(d).setScale(1, RoundingMode.HALF_UP))));
    }

    private VisitReport pendingReport(Long id) {
        VisitReport r = reports.findById(id).orElseThrow(() -> ApiException.notFound("Report not found."));
        if (!VisitReport.SUBMITTED.equals(r.getStatus())) {
            throw ApiException.conflict("This report has already been " + r.getStatus().toLowerCase() + ".");
        }
        return r;
    }

    private java.util.Optional<Long> providerUser(Long providerId) {
        return providers.findById(providerId).map(Provider::getUserId);
    }

    private List<ReportRow> rows(List<VisitReport> list) {
        Map<Long, Provider> provs = byId(providers.findAllById(ids(list.stream().map(VisitReport::getProviderId).toList())), Provider::getId);
        Map<Long, CareRequest> reqs = byId(requests.findAllById(ids(list.stream().map(VisitReport::getCareRequestId).toList())), CareRequest::getId);
        return list.stream().map(r -> {
            Provider p = provs.get(r.getProviderId());
            CareRequest req = reqs.get(r.getCareRequestId());
            return new ReportRow(r.getId(), r.getCareRequestId(), r.getVisitId(), r.getProviderId(),
                p == null ? null : p.getFullName(), req == null ? null : req.getFullName(),
                req == null ? null : req.getServiceNeeded(), r.getVisitDate(), r.activityList(), r.getPatientCondition(),
                r.getMedicationGiven(), r.getNotes(), r.getNextSteps(), r.getBloodPressure(), r.getPulse(),
                r.getTemperature(), r.getSpo2(), r.getBloodSugar(), r.getRespiratoryRate(), r.getStatus(),
                r.getReviewNote(), r.getReviewedAt(), r.getCreatedAt());
        }).toList();
    }

    private static <T> Map<Long, T> byId(List<T> list, Function<T, Long> id) {
        return list.stream().collect(Collectors.toMap(id, Function.identity()));
    }

    private static List<Long> ids(Collection<Long> raw) {
        return raw.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || to.isAfter(from.plusDays(366))) {
            throw ApiException.badRequest("Choose a date range of up to one year.");
        }
    }

    private static String type(Provider p) {
        return p.getProviderType() != null ? p.getProviderType() : p.getServiceType();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
