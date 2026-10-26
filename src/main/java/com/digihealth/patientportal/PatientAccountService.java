package com.digihealth.patientportal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.carerequest.CareRequest;
import com.digihealth.carerequest.CareRequestDtos;
import com.digihealth.carerequest.CareRequestRepository;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.hmo.HmoOrganisation;
import com.digihealth.hmo.HmoOrganisationRepository;
import com.digihealth.hmo.HmoPlan;
import com.digihealth.hmo.HmoPlanRepository;
import com.digihealth.hmo.HmoSubscription;
import com.digihealth.hmo.HmoSubscriptionRepository;
import com.digihealth.patient.Patient;
import com.digihealth.patient.PatientRepository;
import com.digihealth.payment.PaymentRepository;
import com.digihealth.provider.Provider;
import com.digihealth.provider.ProviderRepository;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;
import com.digihealth.visit.VisitReport;
import com.digihealth.visit.VisitReportRepository;

/** My Profile, documents, results, medications, bills and payments for the signed-in patient. */
@Service
public class PatientAccountService {

    private final UserRepository users;
    private final PatientRepository patients;
    private final HmoSubscriptionRepository subscriptions;
    private final HmoPlanRepository plans;
    private final HmoOrganisationRepository organisations;
    private final PaymentRepository payments;
    private final VisitReportRepository reports;
    private final CareRequestRepository requests;
    private final ProviderRepository providers;
    private final ActivityService activity;

    public PatientAccountService(UserRepository users, PatientRepository patients, HmoSubscriptionRepository subscriptions,
                                 HmoPlanRepository plans, HmoOrganisationRepository organisations,
                                 PaymentRepository payments, VisitReportRepository reports,
                                 CareRequestRepository requests, ProviderRepository providers, ActivityService activity) {
        this.users = users;
        this.patients = patients;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.organisations = organisations;
        this.payments = payments;
        this.reports = reports;
        this.requests = requests;
        this.providers = providers;
        this.activity = activity;
    }

    /* ── DTOs ── */

    public record NextOfKin(@Size(max = 150) String name, @Size(max = 60) String relationship,
                            @Pattern(regexp = CareRequestDtos.PHONE_RULE, message = "Enter a valid phone number") String phone) {
    }

    public record Preferences(Boolean sms, Boolean whatsapp, Boolean email) {
    }

    public record ProfileBody(
            @NotBlank(message = "Enter a valid phone number") @Pattern(regexp = CareRequestDtos.PHONE_RULE, message = "Enter a valid phone number") String phoneNumber,
            @Email(message = "Enter a valid email") String email,
            String dateOfBirth,
            @Pattern(regexp = "^(FEMALE|MALE)$", message = "Choose female or male") String gender,
            @NotBlank(message = "Enter your full address so nurses can find you")
            @Size(min = 8, max = 300, message = "Enter your full address so nurses can find you") String address,
            @Size(max = 100) String areaLga,
            @Size(max = 200) String landmark,
            @Valid NextOfKin nextOfKin,
            @Pattern(regexp = "^(A|B|AB|O)[+-]$", message = "Choose a blood group") String bloodGroup,
            @Pattern(regexp = "^(AA|AS|SS|AC|SC)$", message = "Choose a genotype") String genotype,
            List<@Size(max = 100) String> allergies,
            List<@Size(max = 100) String> conditions,
            List<@Size(max = 100) String> currentMedications,
            @Size(max = 150) String primaryDoctor,
            Preferences notificationPreferences) {
    }

    public record NextOfKinView(String name, String relationship, String phone) {
    }

    public record ProfileView(Long id, String patientNumber, String fullName, String email, String phoneNumber,
                              LocalDate dateOfBirth, String gender, String address, String areaLga, String landmark,
                              String locationArea, NextOfKinView nextOfKin, String bloodGroup, String genotype,
                              List<String> allergies, List<String> conditions, List<String> currentMedications,
                              String primaryDoctor, Map<String, Boolean> notificationPreferences, String patientType,
                              String hmoName, String hmoPlan, String hmoMemberId, LocalDate hmoExpiryDate,
                              BigDecimal benefitRemaining, Instant createdAt) {
    }

    public record DocumentView(String id, String type, String title, String authorName, LocalDate date,
                               Instant createdAt, String url, Long visitId) {
    }

    public record PaymentView(Long id, String reference, BigDecimal amount, String method, String description,
                              String status, Instant paidAt, Instant createdAt) {
    }

    /* ── Profile ── */

    @Transactional(readOnly = true)
    public ProfileView profile() {
        User u = users.findById(CurrentUser.id()).orElseThrow();
        Patient p = patients.findByUserId(u.getId()).orElse(null);
        return view(u, p);
    }

    @Transactional
    public ProfileView update(ProfileBody body) {
        User u = users.findById(CurrentUser.id()).orElseThrow();
        Patient p = patients.findByUserId(u.getId()).orElseGet(() -> patients.save(new Patient(u.getId(), Patient.STANDARD)));

        if (body.email() != null && !body.email().isBlank()
                && !User.normalizeEmail(body.email()).equals(u.getEmail())) {
            throw ApiException.badRequest("Your email is your login, so it can't be changed here. Contact support to change it.");
        }
        LocalDate dob = AppTime.parseDate(body.dateOfBirth(), "dateOfBirth");
        if (dob != null && dob.isAfter(AppTime.today())) {
            throw ApiException.badRequest("Date of birth can't be in the future.");
        }
        NextOfKin nok = body.nextOfKin();
        if (nok != null && blank(nok.name()) && !blank(nok.phone())) {
            throw ApiException.badRequest("Enter your next of kin's name.");
        }

        u.setPhoneNumber(body.phoneNumber().trim());
        p.setDateOfBirth(dob);
        p.setGender(text(body.gender()));
        p.setAddress(body.address().trim());
        p.setAreaLga(text(body.areaLga()));
        p.setLandmark(text(body.landmark()));
        p.setNextOfKinName(nok == null ? null : text(nok.name()));
        p.setNextOfKinRelationship(nok == null ? null : text(nok.relationship()));
        p.setNextOfKinPhone(nok == null ? null : text(nok.phone()));
        p.setBloodGroup(text(body.bloodGroup()));
        p.setGenotype(text(body.genotype()));
        p.setAllergies(join(body.allergies()));
        p.setConditions(join(body.conditions()));
        p.setCurrentMedications(join(body.currentMedications()));
        p.setPrimaryDoctor(text(body.primaryDoctor()));
        Preferences prefs = body.notificationPreferences();
        if (prefs != null) {
            p.setNotifySms(!Boolean.FALSE.equals(prefs.sms()));
            p.setNotifyWhatsapp(!Boolean.FALSE.equals(prefs.whatsapp()));
            p.setNotifyEmail(!Boolean.FALSE.equals(prefs.email()));
        }
        activity.record("PATIENT_PROFILE_UPDATED", "Updated their profile", "USER", u.getId());
        return view(u, p);
    }

    /* ── Documents: approved visit reports for now (lab reports etc. arrive in Phase 11) ── */

    @Transactional(readOnly = true)
    public List<DocumentView> documents() {
        Long me = CurrentUser.id();
        List<VisitReport> list = reports.findByPatientIdAndStatusOrderByVisitDateDesc(me, VisitReport.APPROVED);
        Map<Long, String> names = providers.findAllById(list.stream().map(VisitReport::getProviderId).distinct().toList())
            .stream().collect(Collectors.toMap(Provider::getId, Provider::getFullName));
        Map<Long, CareRequest> reqs = requests.findAllById(list.stream().map(VisitReport::getCareRequestId).distinct().toList())
            .stream().collect(Collectors.toMap(CareRequest::getId, Function.identity()));
        return list.stream().map(r -> {
            CareRequest req = reqs.get(r.getCareRequestId());
            String service = req == null ? "Care" : PatientBookingService.label(req.getServiceNeeded());
            return new DocumentView("VR-" + r.getId(), "VISIT_REPORT", service + " visit report", names.get(r.getProviderId()),
                r.getVisitDate(), r.getCreatedAt(), null, r.getVisitId());
        }).toList();
    }

    /* ── Bills and payments ── */

    @Transactional(readOnly = true)
    public List<PaymentView> payments() {
        return payments.findByPayerUserIdOrderByCreatedAtDesc(CurrentUser.id()).stream()
            .map(p -> new PaymentView(p.getId(), p.getReference(), p.getAmount(), p.getMethod(), p.getDescription(),
                p.getStatus(), p.getPaidAt(), p.getCreatedAt()))
            .toList();
    }

    /** Patient invoices and online payment arrive with Finance and Paystack (Phase 12). */
    public Map<String, String> pay(Long invoiceId) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "Online payment isn't available yet. Please pay by bank transfer and send the receipt through Contact Support.");
    }

    /* ── helpers ── */

    private ProfileView view(User u, Patient p) {
        String type = p == null ? Patient.STANDARD : p.getPatientType();
        HmoSubscription sub = currentSubscription(u.getId(), p);
        HmoPlan plan = sub == null ? null : plans.findById(sub.getPlanId()).orElse(null);
        String hmoName = null;
        Long orgId = p == null ? null : p.getHmoOrganisationId();
        if (sub != null && sub.getOrganisationId() != null) {
            orgId = sub.getOrganisationId();
        }
        if (orgId != null) {
            hmoName = organisations.findById(orgId).map(HmoOrganisation::getCompanyName).orElse(null);
        } else if (sub != null) {
            hmoName = "DiGi Health HMO";
        }
        String number = String.format("DH-%06d", u.getId());
        return new ProfileView(u.getId(), number, u.getFullName(), u.getEmail(), u.getPhoneNumber(),
            p == null ? null : p.getDateOfBirth(), p == null ? null : p.getGender(), p == null ? null : p.getAddress(),
            p == null ? null : p.getAreaLga(), p == null ? null : p.getLandmark(), u.getLocationArea(),
            p == null ? new NextOfKinView(null, null, null)
                : new NextOfKinView(p.getNextOfKinName(), p.getNextOfKinRelationship(), p.getNextOfKinPhone()),
            p == null ? null : p.getBloodGroup(), p == null ? null : p.getGenotype(),
            split(p == null ? null : p.getAllergies()), split(p == null ? null : p.getConditions()),
            split(p == null ? null : p.getCurrentMedications()), p == null ? null : p.getPrimaryDoctor(),
            Map.of("sms", p == null || p.isNotifySms(), "whatsapp", p == null || p.isNotifyWhatsapp(),
                "email", p == null || p.isNotifyEmail()),
            type, hmoName, plan == null ? null : plan.getName(), Patient.HMO.equals(type) ? number : null,
            sub == null ? null : sub.getExpiryDate(),
            sub == null || plan == null ? null : plan.getBenefitLimit().subtract(sub.getBenefitUsed()).max(BigDecimal.ZERO),
            u.getCreatedAt());
    }

    /** The patient's own plan, or their employer's plan if they're an HMO employee. */
    private HmoSubscription currentSubscription(Long userId, Patient p) {
        LocalDate today = AppTime.today();
        List<HmoSubscription> own = subscriptions.findByPatientIdOrderByExpiryDateDesc(userId);
        List<HmoSubscription> employer = p == null || p.getHmoOrganisationId() == null ? List.of()
            : subscriptions.findByOrganisationIdOrderByExpiryDateDesc(p.getHmoOrganisationId());
        return java.util.stream.Stream.concat(own.stream(), employer.stream())
            .filter(s -> HmoSubscription.ACTIVE.equals(s.effectiveStatus(today)))
            .findFirst()
            .orElse(own.isEmpty() ? null : own.get(0));
    }

    private static List<String> split(String s) {
        return s == null || s.isBlank() ? List.of()
            : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static String join(List<String> items) {
        if (items == null) {
            return null;
        }
        List<String> clean = items.stream().filter(Objects::nonNull).map(String::trim)
            .filter(x -> !x.isEmpty()).map(x -> x.replace(",", " ")).distinct().toList();
        return clean.isEmpty() ? null : String.join(",", clean);
    }

    private static String text(String s) {
        return blank(s) ? null : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
