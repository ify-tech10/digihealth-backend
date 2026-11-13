package com.digihealth.rm;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.admin.AdminHmoService;
import com.digihealth.auth.PasswordLinks;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.facility.Facility;
import com.digihealth.facility.FacilityKind;
import com.digihealth.facility.FacilityRepository;
import com.digihealth.hmo.HmoApplicationService;
import com.digihealth.hmo.HmoOrganisation;
import com.digihealth.hmo.HmoOrganisationRepository;
import com.digihealth.hmo.HmoPlan;
import com.digihealth.hmo.HmoPlanRepository;
import com.digihealth.hmo.HmoSubscription;
import com.digihealth.hmo.HmoSubscriptionRepository;
import com.digihealth.notification.NotificationService;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

/*
 * Relationship Manager: onboards hospitals, pharmacies, labs and HMO client companies.
 * Facility forms differ per type, so the extra fields are kept as JSON next to the core columns.
 * If an email is given, the facility admin login is created and invited straight away.
 */
@Service
public class RmService {

    private static final Map<FacilityKind, String> NAME_KEY = Map.of(
        FacilityKind.HOSPITAL, "hospitalName", FacilityKind.PHARMACY, "pharmacyName", FacilityKind.LABORATORY, "laboratoryName");
    /* Columns we store directly; everything else goes into details. */
    private static final Set<String> CORE = Set.of("hospitalName", "pharmacyName", "laboratoryName", "address",
        "areaLga", "phoneNumber", "email", "contactPerson", "contactLabManager", "status", "openingHours", "id",
        "createdAt", "kind");

    private final FacilityRepository facilities;
    private final UserRepository users;
    private final HmoOrganisationRepository organisations;
    private final HmoSubscriptionRepository subscriptions;
    private final HmoPlanRepository plans;
    private final HmoApplicationService hmoApplications;
    private final AdminHmoService hmoAdmin;
    private final PasswordLinks passwordLinks;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final ObjectMapper json;

    public RmService(FacilityRepository facilities, UserRepository users, HmoOrganisationRepository organisations,
                     HmoSubscriptionRepository subscriptions, HmoPlanRepository plans,
                     HmoApplicationService hmoApplications, AdminHmoService hmoAdmin, PasswordLinks passwordLinks,
                     NotificationService notifications, ActivityService activity, ObjectMapper json) {
        this.facilities = facilities;
        this.users = users;
        this.organisations = organisations;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.hmoApplications = hmoApplications;
        this.hmoAdmin = hmoAdmin;
        this.passwordLinks = passwordLinks;
        this.notifications = notifications;
        this.activity = activity;
        this.json = json;
    }

    /* ── Facilities ── */

    /** { hospitals: [...], pharmacies: [...], laboratories: [...] } */
    @Transactional(readOnly = true)
    public Map<String, List<Map<String, Object>>> infrastructures() {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("hospitals", new ArrayList<>());
        out.put("pharmacies", new ArrayList<>());
        out.put("laboratories", new ArrayList<>());
        for (Facility f : facilities.findByDeletedAtIsNullOrderByCreatedAtDesc()) {
            out.get(f.getKind().path).add(row(f));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> onboard(String kindPath, Map<String, Object> body) {
        FacilityKind kind = FacilityKind.fromPath(kindPath);
        String name = text(body, NAME_KEY.get(kind));
        String address = text(body, "address");
        String area = text(body, "areaLga");
        String phone = text(body, "phoneNumber");
        String email = text(body, "email");
        String contact = text(body, kind == FacilityKind.LABORATORY ? "contactLabManager" : "contactPerson");

        java.util.Map<String, String> errors = new LinkedHashMap<>();
        if (name == null) {
            errors.put(NAME_KEY.get(kind), kind.label + " name is required");
        }
        if (address == null) {
            errors.put("address", "Address is required");
        }
        if (area == null) {
            errors.put("areaLga", "Area is required");
        }
        if (phone == null || !phone.matches("^[+0-9 ()-]{7,20}$")) {
            errors.put("phoneNumber", "Enter a valid phone number");
        }
        if (email != null && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            errors.put("email", "Enter a valid email");
        }
        if (kind == FacilityKind.PHARMACY && text(body, "nafdacRegNo") == null) {
            errors.put("nafdacRegNo", "Registration number is required");
        }
        if (!errors.isEmpty()) {
            throw new FieldErrors(errors);
        }
        if (facilities.existsByKindAndDeletedAtIsNullAndNameIgnoreCase(kind, name)) {
            throw ApiException.conflict("A " + kind.label.toLowerCase() + " called " + name + " is already onboarded.");
        }
        String normalisedEmail = email == null ? null : User.normalizeEmail(email);
        if (normalisedEmail != null && users.existsByEmail(normalisedEmail)) {
            throw ApiException.conflict(normalisedEmail + " already has a DiGi Health account. Use another email or leave it empty.");
        }
        boolean active = !"inactive".equalsIgnoreCase(text(body, "status"));

        Facility f = new Facility(kind, name);
        f.setAddress(address);
        f.setLocationArea(areaCode(area));
        f.setPhoneNumber(phone);
        f.setEmail(normalisedEmail);
        f.setContactPerson(contact);
        f.setOpeningHours(text(body, "openingHours"));
        f.setActive(active);
        f.setCreatedById(CurrentUser.idOrNull());
        if (kind == FacilityKind.HOSPITAL && body.get("bedCapacity") instanceof Number n) {
            f.setBedsAvailable(n.intValue());
        }
        if (kind == FacilityKind.PHARMACY) {
            f.setRegistrationNumber(text(body, "nafdacRegNo"));
        }
        if (kind == FacilityKind.LABORATORY) {
            f.setRegistrationNumber(text(body, "accreditationBody"));
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("areaLga", area);
        body.forEach((k, v) -> {
            if (!CORE.contains(k) && v != null) {
                extras.put(k, v);
            }
        });
        f.setDetails(json.writeValueAsString(extras));

        if (normalisedEmail != null) {
            User admin = new User(normalisedEmail, contact != null ? contact : name, kind.adminRole);
            admin.setPhoneNumber(phone);
            admin.setLocationArea(f.getLocationArea());
            admin.setActive(active);
            admin.setCreatedById(CurrentUser.idOrNull());
            admin = users.save(admin);
            f.setAdminUserId(admin.getId());
            if (active) {
                passwordLinks.sendInvite(admin);
            }
        }
        f = facilities.save(f);

        notifications.notifyRoles(Role.ADMINS, "New " + kind.label.toLowerCase() + " onboarded",
            name + " (" + area + ") was onboarded by a Relationship Manager"
                + (normalisedEmail == null ? ". Add the admin's email to give them a login." : "."),
            "/admin/" + kind.path);
        activity.record("FACILITY_ONBOARDED", "Onboarded " + kind.label.toLowerCase() + " " + name, kind.name(), f.getId());
        return row(f);
    }

    /* ── Organisations ── */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> organisations() {
        LocalDate today = AppTime.today();
        List<Map<String, Object>> out = new ArrayList<>();
        for (HmoOrganisation o : organisations.findAllByOrderByCreatedAtDesc()) {
            HmoSubscription sub = subscriptions.findByOrganisationIdOrderByExpiryDateDesc(o.getId()).stream()
                .findFirst().orElse(null);
            String planName = sub == null ? null : plans.findById(sub.getPlanId()).map(HmoPlan::getName).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("name", o.getCompanyName());
            m.put("companyName", o.getCompanyName());
            m.put("contactEmail", o.getEmail());
            m.put("email", o.getEmail());
            m.put("phoneNumber", o.getPhoneNumber());
            m.put("companyAddress", o.getCompanyAddress());
            m.put("companySize", o.getCompanySize());
            m.put("industry", o.getIndustry());
            m.put("hrContactName", o.getHrContactName());
            m.put("startDate", o.getStartDate());
            m.put("createdBy", o.getCreatedBy());
            m.put("createdAt", o.getCreatedAt());
            m.put("hmoStatus", o.getStatus());
            m.put("hmoPlan", planName);
            m.put("memberCount", sub == null ? null : sub.getMemberCount());
            m.put("hmoExpiry", sub == null ? null : sub.getExpiryDate());
            m.put("subscriptionStatus", sub == null ? null : sub.effectiveStatus(today));
            out.add(m);
        }
        return out;
    }

    @Transactional
    public HmoApplicationService.Submitted onboardOrganisation(HmoApplicationService.HmoApplication body) {
        return hmoApplications.apply(body, "RELATIONSHIP_MANAGER");
    }

    /** The RM confirms the company: same as Admin approving it (HMO login created and invited). */
    @Transactional
    public void confirmHmo(Long id) {
        hmoAdmin.approve(id);
    }

    /* ── helpers ── */

    private Map<String, Object> row(Facility f) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (f.getDetails() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> extras = json.readValue(f.getDetails(), Map.class);
                m.putAll(extras);
            } catch (RuntimeException ignored) {
                /* details are informational only */
            }
        }
        m.put("id", f.getId());
        m.put("kind", f.getKind().name());
        m.put("name", f.getName());
        m.put(NAME_KEY.get(f.getKind()), f.getName());
        m.put("address", f.getAddress());
        m.putIfAbsent("areaLga", f.getLocationArea());
        m.put("locationArea", f.getLocationArea());
        m.put("phoneNumber", f.getPhoneNumber());
        m.put("email", f.getEmail());
        m.put(f.getKind() == FacilityKind.LABORATORY ? "contactLabManager" : "contactPerson", f.getContactPerson());
        m.put("contactPerson", f.getContactPerson());
        m.put("openingHours", f.getOpeningHours());
        m.put("status", f.isActive() ? "ACTIVE" : "INACTIVE");
        m.put("enabled", f.isActive());
        m.put("hasLogin", f.getAdminUserId() != null);
        m.put("createdAt", f.getCreatedAt());
        return m;
    }

    /** "Eti-Osa" -> ETI_OSA (matches the area codes used everywhere else). */
    static String areaCode(String label) {
        return label.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 400 with { message, errors: { field: message } } so the RM form highlights fields. */
    public static class FieldErrors extends RuntimeException {
        private final Map<String, String> errors;

        public FieldErrors(Map<String, String> errors) {
            super(errors.size() == 1 ? errors.values().iterator().next() : "Please check the highlighted fields.");
            this.errors = errors;
        }

        public Map<String, String> getErrors() {
            return errors;
        }
    }
}
