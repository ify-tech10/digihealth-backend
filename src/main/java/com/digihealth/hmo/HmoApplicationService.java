package com.digihealth.hmo;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.carerequest.CareRequestDtos;
import com.digihealth.common.ApiException;
import com.digihealth.notification.NotificationService;
import com.digihealth.user.Role;
import com.digihealth.user.User;

/** The public HMO form (and, from Phase 11, RM onboarding). Admin approves in Phase 6. */
@Service
public class HmoApplicationService {

    private final HmoOrganisationRepository organisations;
    private final NotificationService notifications;
    private final ActivityService activity;

    public HmoApplicationService(HmoOrganisationRepository organisations, NotificationService notifications,
                                 ActivityService activity) {
        this.organisations = organisations;
        this.notifications = notifications;
        this.activity = activity;
    }

    /** Body of POST /hmo/apply. startDate is "" or yyyy-MM-dd. */
    public record HmoApplication(
            @NotBlank(message = "Company name is required") @Size(max = 200) String companyName,
            @NotBlank(message = "Please select company size") @Size(max = 30) String companySize,
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") @Size(max = 255) String email,
            @NotBlank(message = "Phone number is required") @Pattern(regexp = CareRequestDtos.PHONE_RULE, message = "Enter a valid phone number") String phoneNumber,
            @NotBlank(message = "Company address is required") @Size(max = 300) String companyAddress,
            @Size(max = 150) String hrContactName,
            @Size(max = 100) String industry,
            @Size(max = 10) String startDate) {
    }

    public record Submitted(Long id, String applicationStatus, String message) {
    }

    @Transactional
    public Submitted apply(HmoApplication body, String createdBy) {
        String email = User.normalizeEmail(body.email());
        String name = body.companyName().trim();
        if (organisations.existsActive(email, name, List.of(HmoOrganisation.PENDING, HmoOrganisation.APPROVED))) {
            throw ApiException.conflict("An application for this company is already under review or approved.");
        }

        HmoOrganisation o = new HmoOrganisation(name, body.companySize().trim(), email,
            body.phoneNumber().trim(), body.companyAddress().trim());
        o.setHrContactName(blankToNull(body.hrContactName()));
        o.setIndustry(blankToNull(body.industry()));
        o.setStartDate(parseDate(body.startDate()));
        o.setCreatedBy(createdBy);
        o = organisations.save(o);

        activity.record("HMO_APPLIED", name + " applied for HMO cover", "HMO_ORGANISATION", o.getId());
        notifications.notifyRoles(Role.ADMINS, "New HMO application",
            name + " applied for HMO cover.", "/admin/hmo-coverage");

        return new Submitted(o.getId(), o.getStatus(),
            "Thank you. Our team will contact you within 2 business days.");
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Enter the start date as YYYY-MM-DD.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
