package com.digihealth.admin;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.digihealth.auth.PasswordLinks;
import com.digihealth.auth.RefreshTokenRepository;
import com.digihealth.carerequest.CareRequestDtos;
import com.digihealth.common.ApiException;
import com.digihealth.common.CurrentUser;
import com.digihealth.hmo.HmoOrganisation;
import com.digihealth.hmo.HmoOrganisationRepository;
import com.digihealth.patient.Patient;
import com.digihealth.patient.PatientRepository;
import com.digihealth.provider.Provider;
import com.digihealth.provider.ProviderRepository;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/** Admin > Users and Admin > Patients. Admin creates every staff account. */
@Service
public class AdminUserService {

    /** SUPER_ADMIN is only created at startup (BOOTSTRAP_ADMIN_EMAIL). */
    private static final Set<Role> CREATABLE = EnumSet.complementOf(EnumSet.of(Role.SUPER_ADMIN));

    private final UserRepository users;
    private final ProviderRepository providers;
    private final PatientRepository patients;
    private final HmoOrganisationRepository organisations;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordLinks passwordLinks;
    private final ActivityService activity;

    public AdminUserService(UserRepository users, ProviderRepository providers, PatientRepository patients,
                            HmoOrganisationRepository organisations, RefreshTokenRepository refreshTokens,
                            PasswordLinks passwordLinks, ActivityService activity) {
        this.users = users;
        this.providers = providers;
        this.patients = patients;
        this.organisations = organisations;
        this.refreshTokens = refreshTokens;
        this.passwordLinks = passwordLinks;
        this.activity = activity;
    }

    /* ── DTOs ── */

    public record CreateUser(
            @NotBlank(message = "Full name is required") @Size(max = 150) String fullName,
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") @Size(max = 255) String email,
            @NotBlank(message = "Phone is required") @Pattern(regexp = CareRequestDtos.PHONE_RULE, message = "Enter a valid phone number") String phoneNumber,
            @NotBlank(message = "Choose a role") String role,
            @Size(max = 60) String locationArea,
            @Size(max = 40) String serviceProviderType,
            @Size(max = 10) String patientType) {
    }

    /** One row of Admin > Users. */
    public record UserView(Long id, String fullName, String email, String phoneNumber, String role,
                           String serviceProviderType, String patientType, String locationArea, boolean active,
                           Instant createdAt, String createdByName, Instant lastLoginAt) {
    }

    /** One row of Admin > Patients. */
    public record PatientView(Long id, Long patientId, String fullName, String email, String phoneNumber,
                              String locationArea, String address, String patientType, Long hmoOrganisationId,
                              String hmoName, boolean active, String status, Instant createdAt,
                              String nextOfKinName, String nextOfKinRelationship, String nextOfKinPhone,
                              boolean consentSigned, Instant consentSignedAt) {
    }

    /* ── Users ── */

    @Transactional(readOnly = true)
    public List<UserView> list(String roleFilter) {
        List<User> list = roleFilter == null || roleFilter.isBlank()
            ? users.findAllByOrderByCreatedAtDesc()
            : users.findByRoleOrderByCreatedAtDesc(parseRole(roleFilter));
        return views(list);
    }

    @Transactional
    public UserView create(CreateUser body) {
        Role role = parseRole(body.role());
        if (!CREATABLE.contains(role)) {
            throw ApiException.badRequest("That role can't be created here.");
        }
        String email = User.normalizeEmail(body.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("A user with this email already exists.");
        }
        boolean isProvider = Role.PROVIDERS.contains(role);
        String providerType = blankToNull(body.serviceProviderType());
        if (isProvider && providerType == null) {
            throw ApiException.badRequest("Choose a provider type.");
        }

        User user = new User(email, body.fullName().trim(), role);
        user.setPhoneNumber(body.phoneNumber().trim());
        user.setLocationArea(blankToNull(body.locationArea()));
        user.setCreatedById(CurrentUser.idOrNull());
        user = users.save(user);

        if (isProvider) {
            Provider p = new Provider(user.getFullName(), email, user.getPhoneNumber());
            p.setUserId(user.getId());
            p.setStatus(Provider.APPROVED);
            p.setProviderType(providerType.toUpperCase());
            p.setServiceType(AdminProviderService.label(providerType));
            p.setLocationArea(user.getLocationArea());
            p.setReviewedById(CurrentUser.idOrNull());
            p.setReviewedAt(Instant.now());
            providers.save(p);
        }
        if (role == Role.PATIENT) {
            String type = Patient.HMO.equalsIgnoreCase(body.patientType()) ? Patient.HMO : Patient.STANDARD;
            patients.save(new Patient(user.getId(), type));
        }

        passwordLinks.sendInvite(user);
        activity.record("USER_CREATED", "Created " + role.name() + " account for " + user.getFullName(), "USER", user.getId());
        return views(List.of(user)).get(0);
    }

    @Transactional
    public void setActive(Long id, boolean active) {
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found."));
        Long me = CurrentUser.id();
        if (!active) {
            if (user.getId().equals(me)) {
                throw ApiException.badRequest("You can't deactivate your own account.");
            }
            if (user.getRole() == Role.SUPER_ADMIN && !CurrentUser.hasRole(Role.SUPER_ADMIN.name())) {
                throw ApiException.forbidden("Only a super admin can deactivate a super admin.");
            }
            if (user.getRole().isAdmin() && users.countByRoleInAndActiveTrue(Role.ADMINS) <= 1) {
                throw ApiException.badRequest("This is the last active admin, so it can't be deactivated.");
            }
        }
        if (user.isActive() == active) {
            return;
        }
        user.setActive(active);
        if (!active) {
            /* Signs them out everywhere within 15 minutes (when the access token expires). */
            refreshTokens.revokeAllForUser(user.getId(), Instant.now());
        }
        activity.record(active ? "USER_ACTIVATED" : "USER_DEACTIVATED",
            (active ? "Reactivated " : "Deactivated ") + user.getFullName(), "USER", user.getId());
    }

    /* ── Patients ── */

    @Transactional(readOnly = true)
    public List<PatientView> patients() {
        List<User> list = users.findByRoleOrderByCreatedAtDesc(Role.PATIENT);
        Map<Long, Patient> byUser = patients.findByUserIdIn(ids(list)).stream()
            .collect(Collectors.toMap(Patient::getUserId, Function.identity()));
        Map<Long, String> hmoNames = organisations.findAllById(byUser.values().stream()
                .map(Patient::getHmoOrganisationId).filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(HmoOrganisation::getId, HmoOrganisation::getCompanyName));

        return list.stream().map(u -> {
            Patient p = byUser.get(u.getId());
            String type = p == null ? Patient.STANDARD : p.getPatientType();
            Long hmoId = p == null ? null : p.getHmoOrganisationId();
            Instant consent = p == null ? null : p.getConsentSignedAt();
            return new PatientView(u.getId(), p == null ? null : p.getId(), u.getFullName(), u.getEmail(),
                u.getPhoneNumber(), u.getLocationArea(), p == null ? null : p.getAddress(), type, hmoId,
                hmoId == null ? null : hmoNames.get(hmoId), u.isActive(), u.isActive() ? "ACTIVE" : "INACTIVE",
                u.getCreatedAt(),
                p == null ? null : p.getNextOfKinName(),
                p == null ? null : p.getNextOfKinRelationship(),
                p == null ? null : p.getNextOfKinPhone(),
                consent != null, consent);
        }).toList();
    }

    /* ── helpers ── */

    private List<UserView> views(List<User> list) {
        List<Long> ids = ids(list);
        Map<Long, String> providerTypes = providers.findByUserIdIn(ids).stream()
            .filter(p -> p.getUserId() != null)
            .collect(Collectors.toMap(Provider::getUserId,
                p -> p.getProviderType() != null ? p.getProviderType() : p.getServiceType(), (a, b) -> a));
        Map<Long, String> patientTypes = patients.findByUserIdIn(ids).stream()
            .collect(Collectors.toMap(Patient::getUserId, Patient::getPatientType));
        Map<Long, String> creators = users.findAllById(list.stream().map(User::getCreatedById)
                .filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(User::getId, User::getFullName));

        return list.stream().map(u -> new UserView(u.getId(), u.getFullName(), u.getEmail(), u.getPhoneNumber(),
            u.getRole().name(), providerTypes.get(u.getId()), patientTypes.get(u.getId()), u.getLocationArea(),
            u.isActive(), u.getCreatedAt(),
            u.getCreatedById() == null ? null : creators.get(u.getCreatedById()), u.getLastLoginAt())).toList();
    }

    private static List<Long> ids(Collection<User> list) {
        return list.stream().map(User::getId).toList();
    }

    static Role parseRole(String value) {
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Unknown role: " + value);
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
