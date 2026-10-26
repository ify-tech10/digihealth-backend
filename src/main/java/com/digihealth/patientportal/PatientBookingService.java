package com.digihealth.patientportal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
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
 * The patient's bookings (their care requests), visits and care plan.
 * A booking is REQUESTED until a caregiver is assigned, then CONFIRMED,
 * IN_PROGRESS while a visit is happening, then COMPLETED (or CANCELLED).
 * Visit details (vitals, summary) only appear once clinical leadership approves the report.
 */
@Service
public class PatientBookingService {

    static final Set<String> SERVICES = Set.of("POST_DISCHARGE_RECOVERY", "CHRONIC_DISEASE_MANAGEMENT", "ELDERLY_CARE",
        "POSTNATAL_NEWBORN_CARE", "PHYSIOTHERAPY", "LAB_DIAGNOSTICS", "TELEMEDICINE");
    private static final Map<String, Integer> SLOT_HOUR = Map.of("MORNING", 8, "AFTERNOON", 12, "EVENING", 16);
    private static final Duration CHANGE_CUTOFF = Duration.ofHours(12);

    private final CareRequestRepository requests;
    private final VisitRepository visits;
    private final VisitReportRepository reports;
    private final ProviderRepository providers;
    private final UserRepository users;
    private final NotificationService notifications;
    private final ActivityService activity;

    public PatientBookingService(CareRequestRepository requests, VisitRepository visits, VisitReportRepository reports,
                                 ProviderRepository providers, UserRepository users,
                                 NotificationService notifications, ActivityService activity) {
        this.requests = requests;
        this.visits = visits;
        this.reports = reports;
        this.providers = providers;
        this.users = users;
        this.notifications = notifications;
        this.activity = activity;
    }

    /* ── DTOs ── */

    public record Recipient(@NotBlank(message = "Enter their name") @Size(max = 150) String name,
                            @NotBlank(message = "Enter their phone number") @Pattern(regexp = CareRequestDtos.PHONE_RULE, message = "Enter a valid phone number") String phone,
                            @Size(max = 60) String relationship) {
    }

    public record BookBody(@NotBlank(message = "Choose a service") String serviceType,
                           @NotBlank(message = "Choose a date") String date,
                           @NotBlank(message = "Choose a time") String timeSlot,
                           @Size(max = 300) String address,
                           @Size(max = 2000) String notes,
                           Boolean forSomeoneElse,
                           @Valid Recipient careRecipient) {
    }

    public record RescheduleBody(@NotBlank(message = "Choose a date") String date,
                                 @NotBlank(message = "Choose a time") String timeSlot,
                                 @Size(max = 500) String reason) {
    }

    public record FeedbackBody(@NotNull(message = "Choose a rating") @Min(1) @Max(5) Integer rating,
                               @Size(max = 2000) String comment) {
    }

    public record Caregiver(String name, String role, String phone, String photoUrl) {
    }

    public record RecipientView(String name, String phone, String relationship) {
    }

    public record BookingView(Long id, String reference, String status, String requestStatus, String serviceType,
                              String title, String date, String timeSlot, Instant scheduledAt, String address,
                              String area, String locationArea, String notes, Caregiver caregiver, Instant createdAt,
                              Instant confirmedAt, String cancelReason, boolean forSomeoneElse,
                              RecipientView careRecipient) {
    }

    public record Vitals(String bloodPressure, Integer pulse, BigDecimal temperature, Integer spo2,
                         BigDecimal bloodSugar, Integer respiratoryRate, BigDecimal weight) {
    }

    public record VisitView(Long id, Long bookingId, String serviceType, String title, Instant scheduledAt,
                            Integer durationMins, Instant checkInAt, Instant checkOutAt, String status,
                            Caregiver caregiver, Vitals vitals, String summary, String nextSteps, String reportUrl,
                            boolean reportReady, Integer rating, String feedback) {
    }

    public record Goal(String text) {
    }

    public record Coordinator(String name, String phone) {
    }

    public record CarePlan(Long id, String programName, String serviceType, LocalDate startDate, LocalDate endDate,
                           String frequency, long visitsPlanned, long visitsCompleted, List<Goal> goals,
                           Coordinator coordinator, String status) {
    }

    /* ── Bookings ── */

    @Transactional(readOnly = true)
    public List<BookingView> bookings() {
        Long me = CurrentUser.id();
        return views(requests.findByPatientIdOrderByCreatedAtDesc(me));
    }

    @Transactional
    public BookingView book(BookBody body) {
        User me = users.findById(CurrentUser.id()).orElseThrow();
        String service = body.serviceType().trim().toUpperCase();
        if (!SERVICES.contains(service)) {
            throw ApiException.badRequest("Choose one of the listed services.");
        }
        LocalDate date = AppTime.parseDate(body.date(), "date");
        String slot = slot(body.timeSlot());
        LocalDate today = AppTime.today();
        if (date.isBefore(today.plusDays(1))) {
            throw ApiException.badRequest("Choose a date from tomorrow. For care today, please call us.");
        }
        if (date.isAfter(today.plusDays(60))) {
            throw ApiException.badRequest("You can book up to 60 days ahead.");
        }
        boolean telemed = "TELEMEDICINE".equals(service);
        String address = blank(body.address()) ? null : body.address().trim();
        if (!telemed && (address == null || address.length() < 8)) {
            throw ApiException.badRequest("Enter the full address where the nurse should come.");
        }
        boolean forOther = Boolean.TRUE.equals(body.forSomeoneElse());
        if (forOther && body.careRecipient() == null) {
            throw ApiException.badRequest("Enter the name and phone number of the person receiving care.");
        }

        String name = forOther ? body.careRecipient().name().trim() : me.getFullName();
        String phone = forOther ? body.careRecipient().phone().trim()
            : me.getPhoneNumber() != null ? me.getPhoneNumber() : "-";
        CareRequest r = new CareRequest(name, me.getEmail(), phone, service, "PORTAL");
        r.setPatientId(me.getId());
        r.setAddress(address);
        r.setLocationArea(me.getLocationArea());
        r.setDescription(blank(body.notes()) ? null : body.notes().trim());
        r.setPreferredDate(date);
        r.setTimeSlot(slot);
        r.setPreferredContactTime(slot);
        r.setCreatedById(me.getId());
        if (forOther) {
            r.setCareRecipientName(name);
            r.setCareRecipientPhone(phone);
            r.setCareRecipientRelationship(blank(body.careRecipient().relationship()) ? null
                : body.careRecipient().relationship().trim());
        }
        r = requests.save(r);

        String msg = me.getFullName() + " booked " + label(service) + " for " + date + " (" + slot.toLowerCase() + ")"
            + (forOther ? " for " + name : "") + (me.getLocationArea() == null ? ". No area on file - confirm it before assigning." : ".");
        notifications.notifyRoles(Role.ADMINS, "New booking", msg, "/admin/care-requests");
        notifications.notifyRoles(Set.of(Role.CUSTOMER_CARE), "New booking", msg, "/ccs/care-requests");
        activity.record("BOOKING_CREATED", msg, "CARE_REQUEST", r.getId());
        return views(List.of(r)).get(0);
    }

    @Transactional
    public BookingView reschedule(Long id, RescheduleBody body) {
        CareRequest r = mine(id);
        requireChangeable(r);
        LocalDate date = AppTime.parseDate(body.date(), "date");
        LocalDate today = AppTime.today();
        if (date.isBefore(today.plusDays(1)) || date.isAfter(today.plusDays(60))) {
            throw ApiException.badRequest("Choose a date between tomorrow and 60 days ahead.");
        }
        String slot = slot(body.timeSlot());
        r.setPreferredDate(date);
        r.setTimeSlot(slot);
        r.setPreferredContactTime(slot);

        String when = date + " (" + slot.toLowerCase() + ")";
        String why = blank(body.reason()) ? "" : " Reason: " + body.reason().trim();
        int cancelled = cancelUpcomingVisits(r);
        Long providerUser = providerUserId(r);
        if (providerUser != null) {
            notifications.notify(providerUser, "Patient asked to reschedule",
                r.getFullName() + " asked to move their visit to " + when + "."
                    + (cancelled > 0 ? " Their upcoming visit was cancelled; please book the new time." : "") + why,
                "/caregiver/schedule");
        } else {
            notifications.notifyRoles(Role.ADMINS, "Booking date changed", r.getFullName() + " moved their booking to " + when + "." + why,
                "/admin/care-requests");
        }
        activity.record("BOOKING_RESCHEDULED", r.getFullName() + " moved booking to " + when, "CARE_REQUEST", r.getId());
        return views(List.of(r)).get(0);
    }

    @Transactional
    public BookingView cancel(Long id, String reason) {
        CareRequest r = mine(id);
        if (!CareRequest.PENDING.equals(r.getStatus()) && !CareRequest.ASSIGNED.equals(r.getStatus())) {
            throw ApiException.conflict("This booking can no longer be cancelled.");
        }
        if (inProgress(r)) {
            throw ApiException.conflict("A visit is in progress, so this booking can't be cancelled right now.");
        }
        r.setStatus(CareRequest.CANCELLED);
        r.setCancelReason(blank(reason) ? null : reason.trim());
        r.setCancelledAt(Instant.now());
        cancelUpcomingVisits(r);

        String msg = r.getFullName() + " cancelled their " + label(r.getServiceNeeded()) + " booking"
            + (r.getCancelReason() == null ? "." : ": " + r.getCancelReason());
        Long providerUser = providerUserId(r);
        if (providerUser != null) {
            notifications.notify(providerUser, "Booking cancelled", msg, "/caregiver/patients");
        }
        notifications.notifyRoles(Role.ADMINS, "Booking cancelled", msg, "/admin/care-requests");
        activity.record("BOOKING_CANCELLED", msg, "CARE_REQUEST", r.getId());
        return views(List.of(r)).get(0);
    }

    /* ── Visits ── */

    @Transactional(readOnly = true)
    public List<VisitView> visits() {
        Long me = CurrentUser.id();
        List<Visit> list = visits.findByPatientIdOrderByScheduledAtDesc(me);
        Map<Long, Provider> provs = providersById(list.stream().map(Visit::getProviderId).toList());
        Map<Long, VisitReport> approved = reports.findByVisitIdIn(list.stream().map(Visit::getId).toList()).stream()
            .filter(r -> VisitReport.APPROVED.equals(r.getStatus()))
            .collect(Collectors.toMap(VisitReport::getVisitId, Function.identity(), (a, b) -> a));
        return list.stream().map(v -> {
            VisitReport rep = approved.get(v.getId());
            Provider p = provs.get(v.getProviderId());
            return new VisitView(v.getId(), v.getCareRequestId(), v.getServiceType(), v.getTitle(), v.getScheduledAt(),
                v.getDurationMinutes(), v.getCheckInAt(), v.getCheckOutAt(), v.getStatus(), caregiver(p, false),
                rep == null ? null : new Vitals(rep.getBloodPressure(), rep.getPulse(), rep.getTemperature(), rep.getSpo2(),
                    rep.getBloodSugar(), rep.getRespiratoryRate(), null),
                rep == null ? null : rep.getNotes(), rep == null ? null : rep.getNextSteps(), null, rep != null,
                v.getRating() == null ? null : v.getRating().intValue(), v.getFeedbackComment());
        }).toList();
    }

    @Transactional
    public VisitView rate(Long visitId, FeedbackBody body) {
        Long me = CurrentUser.id();
        Visit v = visits.findById(visitId).filter(x -> me.equals(x.getPatientId()))
            .orElseThrow(() -> ApiException.notFound("Visit not found."));
        if (!Visit.COMPLETED.equals(v.getStatus())) {
            throw ApiException.badRequest("You can rate a visit once it's completed.");
        }
        if (v.getRating() != null) {
            throw ApiException.conflict("You've already rated this visit. Thank you!");
        }
        v.setRating(body.rating().shortValue());
        v.setFeedbackComment(blank(body.comment()) ? null : body.comment().trim());
        v.setRatedAt(Instant.now());

        Provider p = providers.findById(v.getProviderId()).orElse(null);
        if (p != null && p.getUserId() != null) {
            notifications.notify(p.getUserId(), "New visit rating",
                "A patient rated your visit " + body.rating() + " out of 5.", "/caregiver");
        }
        if (body.rating() <= 2) {
            notifications.notifyRoles(Set.of(Role.CUSTOMER_CARE), "Low visit rating",
                "A patient rated a visit " + body.rating() + "/5"
                    + (v.getFeedbackComment() == null ? "." : ": \"" + v.getFeedbackComment() + "\""), "/ccs");
        }
        activity.record("VISIT_RATED", "Rated a visit " + body.rating() + "/5", "VISIT", v.getId());
        return visits().stream().filter(x -> x.id().equals(v.getId())).findFirst().orElseThrow();
    }

    /* ── Care plan: the current (or most recent) care request ── */

    @Transactional(readOnly = true)
    public CarePlan carePlan() {
        Long me = CurrentUser.id();
        List<CareRequest> list = requests.findByPatientIdOrderByCreatedAtDesc(me);
        CareRequest r = list.stream()
            .filter(x -> CareRequest.ASSIGNED.equals(x.getStatus()) || CareRequest.PENDING_CLOSURE.equals(x.getStatus()))
            .findFirst()
            .orElse(list.stream().filter(x -> CareRequest.COMPLETED.equals(x.getStatus())).findFirst().orElse(null));
        if (r == null) {
            return null;
        }
        List<Visit> vs = visits.findByCareRequestIdOrderByScheduledAtAsc(r.getId());
        long planned = vs.stream().filter(v -> !Visit.CANCELLED.equals(v.getStatus())).count();
        long done = vs.stream().filter(v -> Visit.COMPLETED.equals(v.getStatus())).count();
        List<Goal> goals = reports.findByCareRequestIdAndStatusOrderByVisitDateDesc(r.getId(), VisitReport.APPROVED).stream()
            .map(VisitReport::getNextSteps).filter(Objects::nonNull).findFirst()
            .map(s -> s.lines().map(String::trim).filter(l -> !l.isEmpty()).map(Goal::new).toList())
            .orElse(List.of());
        Provider p = r.getAssignedProviderId() == null ? null : providers.findById(r.getAssignedProviderId()).orElse(null);
        return new CarePlan(r.getId(), label(r.getServiceNeeded()), r.getServiceNeeded(),
            r.getAssignedAt() == null ? null : LocalDate.ofInstant(r.getAssignedAt(), AppTime.ZONE),
            r.getClosedAt() == null ? null : LocalDate.ofInstant(r.getClosedAt(), AppTime.ZONE),
            null, planned, done, goals, p == null ? null : new Coordinator(p.getFullName(), p.getPhoneNumber()),
            r.getStatus());
    }

    /* ── helpers ── */

    private List<BookingView> views(List<CareRequest> list) {
        List<Long> ids = list.stream().map(CareRequest::getId).toList();
        Map<Long, List<Visit>> byRequest = ids.isEmpty() ? new HashMap<>()
            : visits.findByCareRequestIdIn(ids).stream().collect(Collectors.groupingBy(Visit::getCareRequestId));
        Map<Long, Provider> provs = providersById(list.stream().map(CareRequest::getAssignedProviderId).toList());
        Instant now = Instant.now();

        return list.stream().map(r -> {
            List<Visit> vs = byRequest.getOrDefault(r.getId(), List.of());
            boolean live = vs.stream().anyMatch(v -> Visit.IN_PROGRESS.equals(v.getStatus()));
            Visit next = vs.stream().filter(v -> Visit.SCHEDULED.equals(v.getStatus()) && v.getScheduledAt().isAfter(now))
                .min(Comparator.comparing(Visit::getScheduledAt)).orElse(null);
            String status = switch (r.getStatus()) {
                case CareRequest.PENDING -> "REQUESTED";
                case CareRequest.ASSIGNED, CareRequest.PENDING_CLOSURE -> live ? "IN_PROGRESS" : "CONFIRMED";
                default -> r.getStatus();
            };
            String date = next != null ? LocalDate.ofInstant(next.getScheduledAt(), AppTime.ZONE).toString()
                : r.getPreferredDate() == null ? null : r.getPreferredDate().toString();
            boolean forOther = r.getCareRecipientName() != null;
            return new BookingView(r.getId(), String.format("BK-%06d", r.getId()), status, r.getStatus(),
                r.getServiceNeeded(), next == null ? null : next.getTitle(), date, r.getTimeSlot(),
                next == null ? null : next.getScheduledAt(), r.getAddress(), r.getLocationArea(), r.getLocationArea(),
                r.getDescription(), caregiver(provs.get(r.getAssignedProviderId()), true), r.getCreatedAt(),
                r.getAssignedAt(), r.getCancelReason(), forOther,
                forOther ? new RecipientView(r.getCareRecipientName(), r.getCareRecipientPhone(),
                    r.getCareRecipientRelationship()) : null);
        }).toList();
    }

    private CareRequest mine(Long id) {
        return requests.findByIdAndPatientId(id, CurrentUser.id())
            .orElseThrow(() -> ApiException.notFound("Booking not found."));
    }

    private void requireChangeable(CareRequest r) {
        if (!CareRequest.PENDING.equals(r.getStatus()) && !CareRequest.ASSIGNED.equals(r.getStatus())) {
            throw ApiException.conflict("This booking can no longer be changed.");
        }
        if (inProgress(r)) {
            throw ApiException.conflict("A visit is in progress, so this booking can't be changed right now.");
        }
        Instant cutoff = Instant.now().plus(CHANGE_CUTOFF);
        boolean soon = visits.findByCareRequestIdOrderByScheduledAtAsc(r.getId()).stream()
            .anyMatch(v -> Visit.SCHEDULED.equals(v.getStatus()) && v.getScheduledAt().isAfter(Instant.now())
                && v.getScheduledAt().isBefore(cutoff));
        if (!soon && r.getPreferredDate() != null && r.getTimeSlot() != null
                && CareRequest.PENDING.equals(r.getStatus())) {
            Instant preferred = r.getPreferredDate().atTime(LocalTime.of(SLOT_HOUR.getOrDefault(r.getTimeSlot(), 8), 0))
                .atZone(AppTime.ZONE).toInstant();
            soon = preferred.isAfter(Instant.now()) && preferred.isBefore(cutoff);
        }
        if (soon) {
            throw ApiException.conflict("Your visit is less than 12 hours away. Please call us to change it.");
        }
    }

    private boolean inProgress(CareRequest r) {
        return visits.findByCareRequestIdOrderByScheduledAtAsc(r.getId()).stream()
            .anyMatch(v -> Visit.IN_PROGRESS.equals(v.getStatus()));
    }

    private int cancelUpcomingVisits(CareRequest r) {
        int n = 0;
        for (Visit v : visits.findByCareRequestIdOrderByScheduledAtAsc(r.getId())) {
            if (Visit.SCHEDULED.equals(v.getStatus()) && v.getScheduledAt().isAfter(Instant.now())) {
                v.setStatus(Visit.CANCELLED);
                n++;
            }
        }
        return n;
    }

    private Long providerUserId(CareRequest r) {
        return r.getAssignedProviderId() == null ? null
            : providers.findById(r.getAssignedProviderId()).map(Provider::getUserId).orElse(null);
    }

    private Map<Long, Provider> providersById(Collection<Long> ids) {
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        return wanted.isEmpty() ? new HashMap<>()
            : providers.findAllById(wanted).stream().collect(Collectors.toMap(Provider::getId, Function.identity()));
    }

    private static Caregiver caregiver(Provider p, boolean withPhone) {
        if (p == null) {
            return null;
        }
        String role = p.getProviderType() != null ? p.getProviderType() : p.getServiceType();
        return new Caregiver(p.getFullName(), "REGISTERED_NURSE".equals(role) ? "RN" : role,
            withPhone ? p.getPhoneNumber() : null, null);
    }

    private static String slot(String text) {
        String s = text.trim().toUpperCase();
        if (!SLOT_HOUR.containsKey(s)) {
            throw ApiException.badRequest("Choose morning, afternoon or evening.");
        }
        return s;
    }

    static String label(String code) {
        if (code == null) {
            return "care";
        }
        String s = code.replace('_', ' ').toLowerCase();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
