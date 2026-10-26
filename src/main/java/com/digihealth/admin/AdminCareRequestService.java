package com.digihealth.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.carerequest.CareRequest;
import com.digihealth.carerequest.CareRequestRepository;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.email.EmailService;
import com.digihealth.notification.NotificationService;
import com.digihealth.provider.Provider;
import com.digihealth.provider.ProviderRepository;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;
import com.digihealth.visit.Visit;
import com.digihealth.visit.VisitRepository;

/*
 * Admin > Care Requests and Admin > Schedule.
 * PENDING -> (assign) ASSIGNED -> (provider closes) PENDING_CLOSURE
 *         -> (approve) COMPLETED, or (send back) ASSIGNED
 */
@Service
public class AdminCareRequestService {

    private static final Set<String> OPEN = Set.of(CareRequest.ASSIGNED, CareRequest.PENDING_CLOSURE);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm").withZone(AppTime.ZONE);

    private final CareRequestRepository requests;
    private final ProviderRepository providers;
    private final UserRepository users;
    private final VisitRepository visits;
    private final NotificationService notifications;
    private final EmailService email;
    private final ActivityService activity;

    public AdminCareRequestService(CareRequestRepository requests, ProviderRepository providers,
                                   UserRepository users, VisitRepository visits,
                                   NotificationService notifications, EmailService email,
                                   ActivityService activity) {
        this.requests = requests;
        this.providers = providers;
        this.users = users;
        this.visits = visits;
        this.notifications = notifications;
        this.email = email;
        this.activity = activity;
    }

    /* ── DTOs ── */

    public record AssignBody(@NotNull(message = "Choose a provider") Long providerId,
                             @Size(max = 1000, message = "Please keep the note under 1,000 characters") String adminNote) {
    }

    public record CareRequestView(Long id, String status, String source, String fullName, String email,
                                  String phoneNumber, String address, String locationArea, String serviceNeeded,
                                  String description, String preferredContactTime, Long patientId,
                                  Long assignedProviderId, String assignedProviderName, String assignedProviderPhone,
                                  Instant assignedAt, String adminNote, String closureNote,
                                  Instant closureRequestedAt, Instant closedAt, String closureReviewNote,
                                  Instant submittedAt) {
    }

    public record AvailableProvider(Long id, String fullName, String serviceProviderType, String locationArea,
                                    String phoneNumber, boolean sameArea, long openRequests) {
    }

    public record VisitView(Long id, Long careRequestId, Long patientId, String patientName, Long providerId,
                            String providerName, String serviceType, String locationArea, String address,
                            Instant scheduledAt, String scheduledTime, Integer durationMinutes, String status,
                            Instant checkInAt, Instant checkOutAt) {
    }

    /* ── Care requests ── */

    @Transactional(readOnly = true)
    public List<CareRequestView> list(String status) {
        List<CareRequest> list = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
            ? requests.findAllByOrderByCreatedAtDesc()
            : requests.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase());
        Map<Long, Provider> byId = providersById(list.stream().map(CareRequest::getAssignedProviderId).toList());
        return list.stream().map(r -> view(r, byId.get(r.getAssignedProviderId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<AvailableProvider> available(Long careRequestId, String location) {
        String area = blank(location) ? null : location.trim();
        if (area == null && careRequestId != null) {
            area = requests.findById(careRequestId).map(CareRequest::getLocationArea).orElse(null);
        }

        List<Provider> approved = providers.findByStatusOrderByCreatedAtDesc(Provider.APPROVED).stream()
            .filter(Provider::isAvailable)
            .filter(p -> p.getUserId() != null)
            .toList();
        Set<Long> activeUsers = users.findAllById(approved.stream().map(Provider::getUserId).toList()).stream()
            .filter(User::isActive).map(User::getId).collect(Collectors.toSet());
        List<Provider> usable = approved.stream().filter(p -> activeUsers.contains(p.getUserId())).toList();

        Map<Long, Long> load = new HashMap<>();
        if (!usable.isEmpty()) {
            for (Object[] row : requests.countOpenByProvider(usable.stream().map(Provider::getId).toList(), OPEN)) {
                load.put((Long) row[0], (Long) row[1]);
            }
        }

        /* Only providers covering the patient's area (home area or a chosen coverage area). */
        String wanted = area;
        if (wanted == null) {
            return List.of();
        }
        return usable.stream()
            .filter(p -> p.covers(wanted))
            .map(p -> new AvailableProvider(p.getId(), p.getFullName(), typeOf(p), p.getLocationArea(),
                p.getPhoneNumber(), true, load.getOrDefault(p.getId(), 0L)))
            /* Whoever has the fewest open requests first. */
            .sorted(Comparator.comparingLong(AvailableProvider::openRequests)
                .thenComparing(AvailableProvider::fullName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    @Transactional
    public CareRequestView assign(Long id, AssignBody body) {
        CareRequest r = find(id);
        if (!CareRequest.PENDING.equals(r.getStatus())) {
            throw ApiException.conflict("Only pending requests can be assigned. This one is " + label(r.getStatus()) + ".");
        }
        Provider p = providers.findById(body.providerId())
            .orElseThrow(() -> ApiException.badRequest("That provider doesn't exist."));
        if (!Provider.APPROVED.equals(p.getStatus()) || p.getUserId() == null) {
            throw ApiException.badRequest(p.getFullName() + " isn't an approved provider.");
        }
        User providerUser = users.findById(p.getUserId()).filter(User::isActive)
            .orElseThrow(() -> ApiException.badRequest(p.getFullName() + "'s account is deactivated."));
        if (r.getLocationArea() == null) {
            throw ApiException.badRequest("This request has no location area, so it can't be matched to a provider. "
                + "Ask Customer Care to confirm the patient's area first.");
        }
        if (!p.covers(r.getLocationArea())) {
            throw ApiException.badRequest(p.getFullName() + " doesn't cover " + AdminProviderService.label(r.getLocationArea())
                + ". Choose a provider in the patient's area.");
        }
        if (!p.isAvailable()) {
            throw ApiException.badRequest(p.getFullName() + " is currently unavailable.");
        }

        r.setAssignedProviderId(p.getId());
        r.setAssignedAt(Instant.now());
        r.setAssignedById(CurrentUser.idOrNull());
        r.setAdminNote(AdminUserService.blankToNull(body.adminNote()));
        r.setStatus(CareRequest.ASSIGNED);

        String service = r.getServiceNeeded();
        notifications.notify(providerUser.getId(), "New patient assigned",
            r.getFullName() + " needs " + service + ". Contact them to schedule the first visit.", "/caregiver/patients");
        if (r.getPatientId() != null) {
            notifications.notify(r.getPatientId(), "Your caregiver is confirmed",
                p.getFullName() + " will handle your " + service + " and will contact you to schedule a visit.",
                "/patient/bookings");
        }
        email.sendNotice(r.getEmail(), r.getFullName(), "Your DiGi Health caregiver is confirmed",
            "Your caregiver is confirmed", List.of(
                p.getFullName() + " (" + AdminProviderService.label(typeOf(p)) + ") has been assigned to your "
                    + service + " request.",
                "They'll contact you shortly to arrange your first visit.",
                "You can follow your booking any time by signing in to DiGi Health."));
        activity.record("CARE_REQUEST_ASSIGNED", "Assigned " + p.getFullName() + " to " + r.getFullName()
            + "'s request (" + service + ")", "CARE_REQUEST", r.getId());
        return view(r, p);
    }

    @Transactional
    public CareRequestView approveClosure(Long id, String note) {
        CareRequest r = find(id);
        requireAwaitingClosure(r);
        r.setStatus(CareRequest.COMPLETED);
        r.setClosedAt(Instant.now());
        r.setClosedById(CurrentUser.idOrNull());
        r.setClosureReviewNote(AdminUserService.blankToNull(note));

        Provider p = r.getAssignedProviderId() == null ? null : providers.findById(r.getAssignedProviderId()).orElse(null);
        if (p != null && p.getUserId() != null) {
            notifications.notify(p.getUserId(), "Request closed",
                r.getFullName() + "'s request has been approved as completed.", "/caregiver/patients");
        }
        if (r.getPatientId() != null) {
            notifications.notify(r.getPatientId(), "Care completed",
                "Your " + r.getServiceNeeded() + " has been marked as completed. Thank you for choosing DiGi Health.",
                "/patient/care-history");
        }
        activity.record("CARE_REQUEST_COMPLETED", "Approved closure of " + r.getFullName() + "'s request",
            "CARE_REQUEST", r.getId());
        return view(r, p);
    }

    @Transactional
    public CareRequestView rejectClosure(Long id, String reason) {
        CareRequest r = find(id);
        requireAwaitingClosure(r);
        String why = AdminUserService.blankToNull(reason);
        r.setStatus(CareRequest.ASSIGNED);
        r.setClosureReviewNote(why);

        Provider p = r.getAssignedProviderId() == null ? null : providers.findById(r.getAssignedProviderId()).orElse(null);
        if (p != null && p.getUserId() != null) {
            notifications.notify(p.getUserId(), "Closure sent back",
                r.getFullName() + "'s request is still open" + (why == null ? "." : ": " + why), "/caregiver/patients");
        }
        activity.record("CARE_REQUEST_CLOSURE_REJECTED", "Sent back closure of " + r.getFullName() + "'s request"
            + (why == null ? "" : " (" + why + ")"), "CARE_REQUEST", r.getId());
        return view(r, p);
    }

    /* ── Schedule ── */

    @Transactional(readOnly = true)
    public List<VisitView> visitsOn(String dateText) {
        LocalDate day = AppTime.parseDate(dateText, "date");
        if (day == null) {
            day = AppTime.today();
        }
        List<Visit> list = visits.findByScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
            AppTime.startOf(day), AppTime.endOf(day));

        Map<Long, Provider> provs = providersById(list.stream().map(Visit::getProviderId).toList());
        Map<Long, String> patientNames = users.findAllById(list.stream().map(Visit::getPatientId)
                .filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(User::getId, User::getFullName));
        Map<Long, CareRequest> reqs = requests.findAllById(list.stream().map(Visit::getCareRequestId)
                .filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(CareRequest::getId, Function.identity()));

        return list.stream().map(v -> {
            Provider p = provs.get(v.getProviderId());
            CareRequest r = v.getCareRequestId() == null ? null : reqs.get(v.getCareRequestId());
            String patient = v.getPatientId() != null && patientNames.containsKey(v.getPatientId())
                ? patientNames.get(v.getPatientId())
                : r == null ? null : r.getFullName();
            return new VisitView(v.getId(), v.getCareRequestId(), v.getPatientId(), patient, v.getProviderId(),
                p == null ? null : p.getFullName(),
                v.getServiceType() != null ? v.getServiceType() : r == null ? null : r.getServiceNeeded(),
                v.getLocationArea() != null ? v.getLocationArea() : r == null ? null : r.getLocationArea(),
                v.getAddress() != null ? v.getAddress() : r == null ? null : r.getAddress(),
                v.getScheduledAt(), HH_MM.format(v.getScheduledAt()), v.getDurationMinutes(), v.getStatus(),
                v.getCheckInAt(), v.getCheckOutAt());
        }).toList();
    }

    /* ── helpers ── */

    private CareRequest find(Long id) {
        return requests.findById(id).orElseThrow(() -> ApiException.notFound("Care request not found."));
    }

    private static void requireAwaitingClosure(CareRequest r) {
        if (!CareRequest.PENDING_CLOSURE.equals(r.getStatus())) {
            throw ApiException.conflict("This request isn't awaiting closure. It's " + label(r.getStatus()) + ".");
        }
    }

    private Map<Long, Provider> providersById(Collection<Long> ids) {
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        return wanted.isEmpty() ? Map.of()
            : providers.findAllById(wanted).stream().collect(Collectors.toMap(Provider::getId, Function.identity()));
    }

    private static CareRequestView view(CareRequest r, Provider p) {
        return new CareRequestView(r.getId(), r.getStatus(), r.getSource(), r.getFullName(), r.getEmail(),
            r.getPhoneNumber(), r.getAddress(), r.getLocationArea(), r.getServiceNeeded(), r.getDescription(),
            r.getPreferredContactTime(), r.getPatientId(), r.getAssignedProviderId(),
            p == null ? null : p.getFullName(), p == null ? null : p.getPhoneNumber(), r.getAssignedAt(),
            r.getAdminNote(), r.getClosureNote(), r.getClosureRequestedAt(), r.getClosedAt(),
            r.getClosureReviewNote(), r.getCreatedAt());
    }

    private static String typeOf(Provider p) {
        return p.getProviderType() != null ? p.getProviderType() : AdminProviderService.typeCode(p.getServiceType());
    }

    private static String label(String status) {
        return status == null ? "unknown" : status.toLowerCase().replace('_', ' ');
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
