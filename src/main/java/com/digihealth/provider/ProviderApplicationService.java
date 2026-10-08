package com.digihealth.provider;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.digihealth.activity.ActivityService;
import com.digihealth.common.ApiException;
import com.digihealth.file.FileService;
import com.digihealth.notification.NotificationService;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/** The public "Apply as a provider" form. Admin reviews it in Phase 6. */
@Service
public class ProviderApplicationService {

    private final ProviderRepository providers;
    private final UserRepository users;
    private final FileService files;
    private final NotificationService notifications;
    private final ActivityService activity;

    public ProviderApplicationService(ProviderRepository providers, UserRepository users, FileService files,
                                      NotificationService notifications, ActivityService activity) {
        this.providers = providers;
        this.users = users;
        this.files = files;
        this.notifications = notifications;
        this.activity = activity;
    }

    public record Submitted(Long id, String status, String message) {
    }

    @Transactional
    public Submitted apply(ProviderApplicationForm f) {
        String email = User.normalizeEmail(f.email());
        if (providers.existsByEmailAndStatusIn(email, List.of(Provider.PENDING))) {
            throw ApiException.conflict("An application with this email is already under review. We'll be in touch soon.");
        }
        if (providers.existsByEmailAndStatusIn(email, List.of(Provider.APPROVED)) || users.existsByEmail(email)) {
            throw ApiException.conflict("This email already has a DiGi Health account. Use another email, or contact us.");
        }

        Provider p = new Provider(f.fullName().trim(), email, f.phoneNumber().trim());
        p.setAddress(f.address().trim());
        p.setGender(text(f.gender()));
        p.setServiceType(f.serviceProviderType().trim());
        p.setYearsOfExperience(f.yearsOfExperience().trim());
        p.setQualification(text(f.qualification()));
        p.setEmploymentStatus(text(f.employmentStatus()));
        p.setSpecialisations(text(f.specialisations()));
        p.setProfessionalSummary(text(f.professionalSummary()));
        p.setAvailabilityType(f.availabilityType().trim());
        p.setPreferredHours(text(f.preferredHours()));
        p.setLocationArea(text(f.locationArea()));
        p.setStartDate(text(f.startDate()));
        p.setAdditionalInfo(text(f.additionalInfo()));
        p.setRef1FullName(text(f.ref1FullName()));
        p.setRef1Relationship(text(f.ref1Relationship()));
        p.setRef1Phone(text(f.ref1Phone()));
        p.setRef1Email(text(f.ref1Email()));
        p.setRef2FullName(text(f.ref2FullName()));
        p.setRef2Relationship(text(f.ref2Relationship()));
        p.setRef2Phone(text(f.ref2Phone()));
        p.setRef2Email(text(f.ref2Email()));

        /* Documents go to Cloudinary privately. */
        p.setCvFileId(upload(f.cv(), true));
        p.setCertificateFileId(upload(f.certificate(), false));
        p.setGovernmentIdFileId(upload(f.governmentId(), false));
        p.setLicenseFileId(upload(f.license(), false));

        p = providers.save(p);

        activity.record(null, "PROVIDER_APPLIED",
            p.getFullName() + " applied as " + p.getServiceType(), "PROVIDER", p.getId());
        notifications.notifyRoles(Role.ADMINS, "New provider application",
            p.getFullName() + " applied as " + p.getServiceType() + ".", "/admin/applications");
        notifications.notifyRoles(Set.of(Role.CNO, Role.CNO_MEDICAL_DIRECTOR), "New provider application",
            p.getFullName() + " applied as " + p.getServiceType() + ".", null);

        return new Submitted(p.getId(), p.getStatus(),
            "Thank you. We review applications within 2 business days.");
    }

    private Long upload(MultipartFile file, boolean required) {
        if (file == null || file.isEmpty()) {
            if (required) {
                throw ApiException.badRequest("CV / Resume is required.");
            }
            return null;
        }
        return files.upload(file, FileService.Kind.DOCUMENT, null).getId();
    }

    /* The form sends placeholders such as "Not provided" for empty optional fields. */
    private static String text(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return switch (t) {
            case "Not provided", "Not specified", "None selected", "None" -> null;
            default -> t;
        };
    }
}
