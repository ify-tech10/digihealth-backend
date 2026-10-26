package com.digihealth.admin;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.auth.PasswordLinks;
import com.digihealth.auth.RefreshTokenRepository;
import com.digihealth.carerequest.CareRequestDtos;
import com.digihealth.common.ApiException;
import com.digihealth.common.CurrentUser;
import com.digihealth.facility.Facility;
import com.digihealth.facility.FacilityKind;
import com.digihealth.facility.FacilityRepository;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/*
 * Admin > Hospitals / Pharmacies / Laboratories.
 * Adding a facility also creates its admin login and emails a set-password link.
 * Deactivating (or deleting) a facility blocks that login.
 */
@Service
public class AdminFacilityService {

    private final FacilityRepository facilities;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordLinks passwordLinks;
    private final ActivityService activity;

    public AdminFacilityService(FacilityRepository facilities, UserRepository users,
                                RefreshTokenRepository refreshTokens, PasswordLinks passwordLinks,
                                ActivityService activity) {
        this.facilities = facilities;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordLinks = passwordLinks;
        this.activity = activity;
    }

    /** Body of POST and PUT /admin/{kind}. email / contactPerson = the facility admin. */
    public record FacilityBody(
            @NotBlank(message = "Name is required") @Size(max = 200) String name,
            @NotBlank(message = "Enter the facility admin's email") @Email(message = "Enter a valid email") @Size(max = 255) String email,
            @NotBlank(message = "Phone is required") @Pattern(regexp = CareRequestDtos.PHONE_RULE, message = "Enter a valid phone number") String phoneNumber,
            @NotBlank(message = "Address is required") @Size(max = 300) String address,
            @NotBlank(message = "Select an area") @Size(max = 60) String locationArea,
            @NotBlank(message = "Enter the facility admin's name") @Size(max = 150) String contactPerson,
            Boolean active) {
    }

    public record FacilityView(Long id, String kind, String name, String email, String phoneNumber, String address,
                               String locationArea, String contactPerson, boolean active, String status,
                               Long adminUserId, Instant createdAt) {
        static FacilityView of(Facility f) {
            return new FacilityView(f.getId(), f.getKind().name(), f.getName(), f.getEmail(), f.getPhoneNumber(),
                f.getAddress(), f.getLocationArea(), f.getContactPerson(), f.isActive(),
                f.isActive() ? "ACTIVE" : "INACTIVE", f.getAdminUserId(), f.getCreatedAt());
        }
    }

    @Transactional(readOnly = true)
    public List<FacilityView> list(String kindPath) {
        FacilityKind kind = FacilityKind.fromPath(kindPath);
        return facilities.findByKindAndDeletedAtIsNullOrderByNameAsc(kind).stream().map(FacilityView::of).toList();
    }

    @Transactional
    public FacilityView create(String kindPath, FacilityBody body) {
        FacilityKind kind = FacilityKind.fromPath(kindPath);
        String name = body.name().trim();
        String email = User.normalizeEmail(body.email());
        if (facilities.existsByKindAndDeletedAtIsNullAndNameIgnoreCase(kind, name)) {
            throw ApiException.conflict("A " + kind.label.toLowerCase() + " called " + name + " already exists.");
        }
        if (users.existsByEmail(email)) {
            throw ApiException.conflict(email + " already has a DiGi Health account. Use a different email for this facility's admin.");
        }
        boolean active = body.active() == null || body.active();

        User admin = new User(email, body.contactPerson().trim(), kind.adminRole);
        admin.setPhoneNumber(body.phoneNumber().trim());
        admin.setLocationArea(body.locationArea().trim());
        admin.setActive(active);
        admin.setCreatedById(CurrentUser.idOrNull());
        admin = users.save(admin);

        Facility f = new Facility(kind, name);
        apply(f, body, email);
        f.setActive(active);
        f.setAdminUserId(admin.getId());
        f.setCreatedById(CurrentUser.idOrNull());
        f = facilities.save(f);

        if (active) {
            passwordLinks.sendInvite(admin);
        }
        activity.record("FACILITY_CREATED", "Added " + kind.label.toLowerCase() + " " + name, kind.name(), f.getId());
        return FacilityView.of(f);
    }

    @Transactional
    public FacilityView update(String kindPath, Long id, FacilityBody body) {
        FacilityKind kind = FacilityKind.fromPath(kindPath);
        Facility f = find(kind, id);
        String email = User.normalizeEmail(body.email());
        String name = body.name().trim();
        if (!f.getName().equalsIgnoreCase(name) && facilities.existsByKindAndDeletedAtIsNullAndNameIgnoreCase(kind, name)) {
            throw ApiException.conflict("A " + kind.label.toLowerCase() + " called " + name + " already exists.");
        }

        User admin = f.getAdminUserId() == null ? null : users.findById(f.getAdminUserId()).orElse(null);
        boolean emailChanged = !Objects.equals(f.getEmail(), email);
        if (emailChanged && users.existsByEmail(email)) {
            throw ApiException.conflict(email + " already has a DiGi Health account. Use a different email.");
        }

        apply(f, body, email);
        boolean active = body.active() == null ? f.isActive() : body.active();
        boolean wasActive = f.isActive();
        f.setActive(active);

        if (admin != null) {
            admin.setFullName(f.getContactPerson());
            admin.setPhoneNumber(f.getPhoneNumber());
            admin.setLocationArea(f.getLocationArea());
            admin.setActive(active);
            if (emailChanged) {
                /* New person or new address: they set a password from the new email. */
                admin.setEmail(email);
                admin.setPasswordHash(null);
                refreshTokens.revokeAllForUser(admin.getId(), Instant.now());
                if (active) {
                    passwordLinks.sendInvite(admin);
                }
            }
            if (wasActive && !active) {
                refreshTokens.revokeAllForUser(admin.getId(), Instant.now());
            }
            if (!wasActive && active && !emailChanged && !admin.hasPassword()) {
                /* Created inactive, so the invite was never sent. */
                passwordLinks.sendInvite(admin);
            }
        }

        activity.record("FACILITY_UPDATED", "Updated " + kind.label.toLowerCase() + " " + f.getName()
            + (wasActive != active ? (active ? " (reactivated)" : " (deactivated)") : ""), kind.name(), f.getId());
        return FacilityView.of(f);
    }

    /** Removes it from the lists and blocks its login; the record stays for invoices and history. */
    @Transactional
    public void delete(String kindPath, Long id) {
        FacilityKind kind = FacilityKind.fromPath(kindPath);
        Facility f = find(kind, id);
        f.setDeletedAt(Instant.now());
        f.setActive(false);
        if (f.getAdminUserId() != null) {
            users.findById(f.getAdminUserId()).ifPresent(u -> {
                u.setActive(false);
                refreshTokens.revokeAllForUser(u.getId(), Instant.now());
            });
        }
        activity.record("FACILITY_DELETED", "Removed " + kind.label.toLowerCase() + " " + f.getName(), kind.name(), f.getId());
    }

    private Facility find(FacilityKind kind, Long id) {
        return facilities.findByIdAndKindAndDeletedAtIsNull(id, kind)
            .orElseThrow(() -> ApiException.notFound(kind.label + " not found."));
    }

    private static void apply(Facility f, FacilityBody body, String email) {
        f.setName(body.name().trim());
        f.setEmail(email);
        f.setPhoneNumber(body.phoneNumber().trim());
        f.setAddress(body.address().trim());
        f.setLocationArea(body.locationArea().trim());
        f.setContactPerson(body.contactPerson().trim());
    }
}
