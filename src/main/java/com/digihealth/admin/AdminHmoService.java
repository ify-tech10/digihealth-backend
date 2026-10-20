package com.digihealth.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.auth.PasswordLinks;
import com.digihealth.common.ApiException;
import com.digihealth.common.CurrentUser;
import com.digihealth.email.EmailService;
import com.digihealth.hmo.HmoOrganisation;
import com.digihealth.hmo.HmoOrganisationRepository;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/** Admin > HMO Onboarding: approve a company and create its HMO login. */
@Service
public class AdminHmoService {

    private final HmoOrganisationRepository organisations;
    private final UserRepository users;
    private final PasswordLinks passwordLinks;
    private final EmailService email;
    private final ActivityService activity;

    public AdminHmoService(HmoOrganisationRepository organisations, UserRepository users,
                           PasswordLinks passwordLinks, EmailService email, ActivityService activity) {
        this.organisations = organisations;
        this.users = users;
        this.passwordLinks = passwordLinks;
        this.email = email;
        this.activity = activity;
    }

    public record HmoView(Long id, String companyName, String companySize, String email, String phoneNumber,
                          String companyAddress, String hrContactName, String industry, LocalDate startDate,
                          String status, String applicationStatus, String createdBy, Long adminUserId,
                          Instant createdAt, Instant reviewedAt, String reviewNote) {
        static HmoView of(HmoOrganisation o) {
            return new HmoView(o.getId(), o.getCompanyName(), o.getCompanySize(), o.getEmail(), o.getPhoneNumber(),
                o.getCompanyAddress(), o.getHrContactName(), o.getIndustry(), o.getStartDate(), o.getStatus(),
                o.getStatus(), o.getCreatedBy(), o.getAdminUserId(), o.getCreatedAt(), o.getReviewedAt(),
                o.getReviewNote());
        }
    }

    @Transactional(readOnly = true)
    public List<HmoView> list(String status) {
        List<HmoOrganisation> list = status == null || status.isBlank()
            ? organisations.findAllByOrderByCreatedAtDesc()
            : organisations.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase());
        return list.stream().map(HmoView::of).toList();
    }

    @Transactional
    public void approve(Long id) {
        HmoOrganisation o = organisations.findById(id).orElseThrow(() -> ApiException.notFound("Application not found."));
        if (!HmoOrganisation.PENDING.equals(o.getStatus())) {
            throw ApiException.conflict("This application has already been " + o.getStatus().toLowerCase() + ".");
        }
        if (users.existsByEmail(o.getEmail())) {
            throw ApiException.conflict("An account with " + o.getEmail() + " already exists. Ask the company for another contact email.");
        }

        String contact = o.getHrContactName() != null ? o.getHrContactName() : o.getCompanyName();
        User admin = new User(o.getEmail(), contact, Role.HMO);
        admin.setPhoneNumber(o.getPhoneNumber());
        admin.setCreatedById(CurrentUser.idOrNull());
        admin = users.save(admin);

        o.setAdminUserId(admin.getId());
        o.setStatus(HmoOrganisation.APPROVED);
        o.setReviewedById(CurrentUser.idOrNull());
        o.setReviewedAt(Instant.now());

        passwordLinks.sendInvite(admin);
        activity.record("HMO_APPROVED", "Approved HMO cover for " + o.getCompanyName(), "HMO_ORGANISATION", o.getId());
    }

    @Transactional
    public void reject(Long id, String reason) {
        HmoOrganisation o = organisations.findById(id).orElseThrow(() -> ApiException.notFound("Application not found."));
        if (!HmoOrganisation.PENDING.equals(o.getStatus())) {
            throw ApiException.conflict("This application has already been " + o.getStatus().toLowerCase() + ".");
        }
        o.setStatus("REJECTED");
        o.setReviewNote(AdminUserService.blankToNull(reason));
        o.setReviewedById(CurrentUser.idOrNull());
        o.setReviewedAt(Instant.now());

        email.sendNotice(o.getEmail(), o.getHrContactName(), "Update on your DiGi Health HMO application",
            "Thank you for your interest", List.of(
                "Thank you for considering DiGi Health for " + o.getCompanyName() + "'s staff healthcare.",
                "We're unable to move forward with this application at the moment. Our team may reach out to discuss other options.",
                "If you have questions, simply reply to this email or contact us through our website."));
        activity.record("HMO_REJECTED", "Rejected HMO application from " + o.getCompanyName(), "HMO_ORGANISATION", o.getId());
    }
}
