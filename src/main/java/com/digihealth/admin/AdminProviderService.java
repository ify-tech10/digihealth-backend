package com.digihealth.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.auth.PasswordLinks;
import com.digihealth.common.ApiException;
import com.digihealth.common.CurrentUser;
import com.digihealth.email.EmailService;
import com.digihealth.file.FileService;
import com.digihealth.provider.Provider;
import com.digihealth.provider.ProviderRepository;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/** Admin > Applications (pending) and Admin > Personnel (approved). */
@Service
public class AdminProviderService {

    private final ProviderRepository providers;
    private final UserRepository users;
    private final FileService files;
    private final PasswordLinks passwordLinks;
    private final EmailService email;
    private final ActivityService activity;

    public AdminProviderService(ProviderRepository providers, UserRepository users, FileService files,
                                PasswordLinks passwordLinks, EmailService email, ActivityService activity) {
        this.providers = providers;
        this.users = users;
        this.files = files;
        this.passwordLinks = passwordLinks;
        this.email = email;
        this.activity = activity;
    }

    /** Field names match the Applications / Personnel pages. Document links expire after an hour. */
    public record ProviderView(Long id, Long userId, String status, String fullName, String email, String phoneNumber,
                               String address, String gender, String serviceProviderType, String serviceType,
                               String yearsOfExperience, String qualification, String employmentStatus,
                               String specialisations, String professionalSummary, String availabilityType,
                               String preferredHours, String locationArea, String startDate, String additionalInfo,
                               String ref1FullName, String ref1Relationship, String ref1Phone, String ref1Email,
                               String ref2FullName, String ref2Relationship, String ref2Phone, String ref2Email,
                               String cvUrl, String certificateUrl, String governmentIdUrl, String licenseUrl,
                               Instant appliedAt, Instant approvedAt, Instant reviewedAt, String reviewNote) {
    }

    @Transactional(readOnly = true)
    public List<ProviderView> list(String status) {
        List<Provider> list = status == null || status.isBlank()
            ? providers.findAllByOrderByCreatedAtDesc()
            : providers.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase());

        List<Long> fileIds = new ArrayList<>();
        for (Provider p : list) {
            fileIds.add(p.getCvFileId());
            fileIds.add(p.getCertificateFileId());
            fileIds.add(p.getGovernmentIdFileId());
            fileIds.add(p.getLicenseFileId());
        }
        Map<Long, String> urls = files.urlsFor(fileIds);
        return list.stream().map(p -> view(p, urls)).toList();
    }

    @Transactional
    public void approve(Long id) {
        Provider p = providers.findById(id).orElseThrow(() -> ApiException.notFound("Application not found."));
        if (!Provider.PENDING.equals(p.getStatus())) {
            throw ApiException.conflict("This application has already been " + p.getStatus().toLowerCase() + ".");
        }
        if (users.existsByEmail(p.getEmail())) {
            throw ApiException.conflict("An account with " + p.getEmail() + " already exists.");
        }

        User user = new User(p.getEmail(), p.getFullName(), Role.SERVICE_PROVIDER);
        user.setPhoneNumber(p.getPhoneNumber());
        user.setLocationArea(p.getLocationArea());
        user.setCreatedById(CurrentUser.idOrNull());
        user = users.save(user);

        p.setUserId(user.getId());
        p.setStatus(Provider.APPROVED);
        if (p.getProviderType() == null) {
            p.setProviderType(typeCode(p.getServiceType()));
        }
        p.setReviewedById(CurrentUser.idOrNull());
        p.setReviewedAt(Instant.now());

        passwordLinks.sendInvite(user);
        activity.record("PROVIDER_APPROVED", "Approved " + p.getFullName() + " (" + p.getServiceType() + ")",
            "PROVIDER", p.getId());
    }

    @Transactional
    public void reject(Long id, String reason) {
        Provider p = providers.findById(id).orElseThrow(() -> ApiException.notFound("Application not found."));
        if (!Provider.PENDING.equals(p.getStatus())) {
            throw ApiException.conflict("This application has already been " + p.getStatus().toLowerCase() + ".");
        }
        p.setStatus(Provider.REJECTED);
        p.setReviewNote(AdminUserService.blankToNull(reason));
        p.setReviewedById(CurrentUser.idOrNull());
        p.setReviewedAt(Instant.now());

        /* The reason stays internal; the applicant gets a polite note. */
        email.sendNotice(p.getEmail(), p.getFullName(), "Update on your DiGi Health application",
            "Thank you for applying", List.of(
                "Thank you for your interest in joining DiGi Health as a care provider.",
                "After reviewing your application, we're unable to move forward with it at this time.",
                "You're welcome to apply again in the future. We wish you every success."));
        activity.record("PROVIDER_REJECTED", "Rejected " + p.getFullName() + "'s application", "PROVIDER", p.getId());
    }

    private static ProviderView view(Provider p, Map<Long, String> urls) {
        String type = p.getProviderType() != null ? p.getProviderType() : p.getServiceType();
        return new ProviderView(p.getId(), p.getUserId(), p.getStatus(), p.getFullName(), p.getEmail(),
            p.getPhoneNumber(), p.getAddress(), p.getGender(), type, p.getServiceType(), p.getYearsOfExperience(),
            p.getQualification(), p.getEmploymentStatus(), p.getSpecialisations(), p.getProfessionalSummary(),
            p.getAvailabilityType(), p.getPreferredHours(), p.getLocationArea(), p.getStartDate(), p.getAdditionalInfo(),
            p.getRef1FullName(), p.getRef1Relationship(), p.getRef1Phone(), p.getRef1Email(),
            p.getRef2FullName(), p.getRef2Relationship(), p.getRef2Phone(), p.getRef2Email(),
            urls.get(p.getCvFileId()), urls.get(p.getCertificateFileId()),
            urls.get(p.getGovernmentIdFileId()), urls.get(p.getLicenseFileId()),
            p.getCreatedAt(), Provider.APPROVED.equals(p.getStatus()) ? p.getReviewedAt() : null,
            p.getReviewedAt(), p.getReviewNote());
    }

    /** "Registered Nurse (RN)" -> REGISTERED_NURSE */
    static String typeCode(String label) {
        if (label == null || label.isBlank()) {
            return "CAREGIVER";
        }
        String code = label.replaceAll("\\(.*?\\)", " ").trim()
            .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        return "OTHER".equals(code) || code.isEmpty() ? "CAREGIVER" : code;
    }

    /** REGISTERED_NURSE -> "Registered Nurse" */
    static String label(String code) {
        String[] words = code.trim().toLowerCase(Locale.ROOT).split("_+");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return out.toString().trim();
    }
}
