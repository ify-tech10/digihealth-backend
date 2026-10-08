package com.digihealth.carerequest;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.auth.PasswordLinks;
import com.digihealth.carerequest.CareRequestDtos.CreateCareRequest;
import com.digihealth.carerequest.CareRequestDtos.CreatedCareRequest;
import com.digihealth.common.CurrentUser;
import com.digihealth.notification.NotificationService;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/*
 * Patient sign-up happens here: there's no separate registration.
 *  - New email: create a PATIENT account (no password yet), email a
 *    set-password link, and link the request to them.
 *  - Existing patient: link the request to them; no email.
 */
@Service
public class CareRequestService {

    private final CareRequestRepository requests;
    private final UserRepository users;
    private final PasswordLinks passwordLinks;
    private final NotificationService notifications;
    private final ActivityService activity;

    public CareRequestService(CareRequestRepository requests, UserRepository users, PasswordLinks passwordLinks,
                              NotificationService notifications, ActivityService activity) {
        this.requests = requests;
        this.users = users;
        this.passwordLinks = passwordLinks;
        this.notifications = notifications;
        this.activity = activity;
    }

    @Transactional
    public CreatedCareRequest create(CreateCareRequest body) {
        String email = User.normalizeEmail(body.email());
        String name = body.fullName().trim();

        boolean accountCreated = false;
        User patient = users.findByEmail(email).orElse(null);
        if (patient == null) {
            patient = new User(email, name, Role.PATIENT);
            patient.setPhoneNumber(body.phoneNumber().trim());
            patient.setLocationArea(blankToNull(body.locationArea()));
            patient = users.save(patient);
            accountCreated = true;
        }

        CareRequest request = new CareRequest(name, email, body.phoneNumber().trim(), body.serviceNeeded().trim(), sourceOf());
        request.setAddress(blankToNull(body.address()));
        request.setLocationArea(blankToNull(body.locationArea()));
        request.setDescription(blankToNull(body.description()));
        request.setPreferredContactTime(blankToNull(body.preferredContactTime()));
        request.setCreatedById(CurrentUser.idOrNull());
        /* Only patients can see requests in the portal; a staff email stays unlinked. */
        if (patient.getRole() == Role.PATIENT) {
            request.setPatientId(patient.getId());
        }
        request = requests.save(request);

        if (accountCreated) {
            passwordLinks.sendInvite(patient);
            activity.record(null, "PATIENT_SIGNED_UP", name + " signed up through Book Care", "USER", patient.getId());
        }
        activity.record("CARE_REQUEST_CREATED",
            name + " requested " + request.getServiceNeeded(), "CARE_REQUEST", request.getId());

        String message = name + " requested " + request.getServiceNeeded()
            + (request.getLocationArea() != null ? " (" + humanize(request.getLocationArea()) + ")" : "") + ".";
        notifications.notifyRoles(Role.ADMINS, "New care request", message, "/admin/care-requests");
        notifications.notifyRoles(Set.of(Role.CUSTOMER_CARE), "New care request", message, "/ccs/care-requests");

        return new CreatedCareRequest(request.getId(), accountCreated);
    }

    /* Signed-in Customer Care logging a phone call vs the public website. */
    private static String sourceOf() {
        return CurrentUser.hasRole(Role.CUSTOMER_CARE.name()) ? "CUSTOMER_CARE" : "WEBSITE";
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String humanize(String code) {
        String s = code.replace('_', ' ').toLowerCase();
        StringBuilder out = new StringBuilder();
        for (String word : s.split(" ")) {
            if (!word.isEmpty()) {
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
            }
        }
        return out.toString().trim();
    }
}
