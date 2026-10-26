package com.digihealth.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.hmo.HmoOrganisation;
import com.digihealth.hmo.HmoOrganisationRepository;
import com.digihealth.hmo.HmoPlan;
import com.digihealth.hmo.HmoPlanRepository;
import com.digihealth.hmo.HmoSubscription;
import com.digihealth.hmo.HmoSubscriptionRepository;
import com.digihealth.notification.NotificationService;
import com.digihealth.patient.Patient;
import com.digihealth.patient.PatientRepository;
import com.digihealth.payment.Payment;
import com.digihealth.payment.PaymentRepository;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/*
 * Admin > HMO Plans and Admin > Subscriptions.
 * A subscription runs for the plan's duration (12 months): start + 12 months - 1 day.
 * Without a payment reference it waits as PENDING_PAYMENT; confirming payment activates it.
 */
@Service
public class AdminHmoPlanService {

    private static final List<String> LIVE = List.of(HmoSubscription.ACTIVE, HmoSubscription.PENDING_PAYMENT);

    private final HmoPlanRepository plans;
    private final HmoSubscriptionRepository subscriptions;
    private final HmoOrganisationRepository organisations;
    private final UserRepository users;
    private final PatientRepository patients;
    private final PaymentRepository payments;
    private final NotificationService notifications;
    private final ActivityService activity;

    public AdminHmoPlanService(HmoPlanRepository plans, HmoSubscriptionRepository subscriptions,
                               HmoOrganisationRepository organisations, UserRepository users,
                               PatientRepository patients, PaymentRepository payments,
                               NotificationService notifications, ActivityService activity) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.organisations = organisations;
        this.users = users;
        this.patients = patients;
        this.payments = payments;
        this.notifications = notifications;
        this.activity = activity;
    }

    /* ── DTOs ── */

    public record PlanBody(
            @NotBlank(message = "Plan name is required") @Size(max = 120) String name,
            @NotNull(message = "Enter the yearly price") @DecimalMin(value = "1", message = "Enter the yearly price") BigDecimal annualPrice,
            @NotNull(message = "Every plan needs a benefit limit") @DecimalMin(value = "1", message = "Every plan needs a benefit limit") BigDecimal benefitLimit,
            @Min(1) @Max(60) Integer durationMonths,
            @Size(max = 4000) String description,
            @NotEmpty(message = "Pick at least one covered service")
            List<@Pattern(regexp = "^[A-Z_]{2,40}$", message = "Unknown service") String> coveredServices,
            Boolean active) {
    }

    public record PlanView(Long id, String name, BigDecimal annualPrice, BigDecimal benefitLimit, int durationMonths,
                           String description, List<String> coveredServices, boolean active, long subscriberCount,
                           Instant createdAt) {
    }

    public record SubscriptionBody(
            @NotBlank(message = "Choose who is subscribing") String subscriberType,
            Long organisationId,
            Long patientId,
            @Min(value = 1, message = "At least 1 member") Integer memberCount,
            @NotNull(message = "Choose a plan") Long planId,
            @NotBlank(message = "Pick a start date") String startDate,
            String expiryDate,
            @Size(max = 100) String paymentReference,
            BigDecimal amountPaid) {
    }

    public record RenewBody(
            @NotBlank(message = "Enter the payment reference") @Size(max = 100) String paymentReference,
            @NotNull(message = "Enter the amount paid") @DecimalMin(value = "1", message = "Enter the amount paid") BigDecimal amountPaid,
            String startDate,
            String expiryDate) {
    }

    public record SubscriptionView(Long id, String subscriberType, String subscriberName, Long organisationId,
                                   Long patientId, Long planId, String planName, BigDecimal annualPrice,
                                   BigDecimal benefitLimit, BigDecimal benefitUsed, BigDecimal benefitRemaining,
                                   int memberCount, LocalDate startDate, LocalDate expiryDate, String status,
                                   String paymentReference, BigDecimal amountPaid, Instant createdAt) {
    }

    /* ── Plans ── */

    @Transactional(readOnly = true)
    public List<PlanView> plans() {
        List<HmoPlan> list = plans.findAllByOrderByActiveDescNameAsc();
        LocalDate today = AppTime.today();
        Map<Long, Long> counts = subscriptions.findByPlanIdIn(list.stream().map(HmoPlan::getId).toList()).stream()
            .filter(s -> HmoSubscription.ACTIVE.equals(s.effectiveStatus(today)))
            .collect(Collectors.groupingBy(HmoSubscription::getPlanId, Collectors.counting()));
        return list.stream().map(p -> planView(p, counts.getOrDefault(p.getId(), 0L))).toList();
    }

    @Transactional
    public PlanView createPlan(PlanBody body) {
        String name = body.name().trim();
        if (plans.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("A plan called " + name + " already exists.");
        }
        HmoPlan p = new HmoPlan(name);
        applyPlan(p, body);
        p = plans.save(p);
        activity.record("HMO_PLAN_CREATED", "Created HMO plan " + name, "HMO_PLAN", p.getId());
        return planView(p, 0);
    }

    @Transactional
    public PlanView updatePlan(Long id, PlanBody body) {
        HmoPlan p = plans.findById(id).orElseThrow(() -> ApiException.notFound("Plan not found."));
        String name = body.name().trim();
        if (plans.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw ApiException.conflict("A plan called " + name + " already exists.");
        }
        applyPlan(p, body);
        activity.record("HMO_PLAN_UPDATED", "Updated HMO plan " + name, "HMO_PLAN", p.getId());
        return planView(p, subscriptions.countByPlanIdAndStatus(p.getId(), HmoSubscription.ACTIVE));
    }

    /* ── Subscriptions ── */

    @Transactional(readOnly = true)
    public List<SubscriptionView> subscriptions(String status) {
        LocalDate today = AppTime.today();
        List<HmoSubscription> list = subscriptions.findAllByOrderByExpiryDateAsc();
        String wanted = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status) ? null : status.trim().toUpperCase();
        if (wanted != null) {
            list = list.stream().filter(s -> wanted.equals(s.effectiveStatus(today))).toList();
        }
        return views(list, today);
    }

    @Transactional
    public SubscriptionView create(SubscriptionBody body) {
        String type = body.subscriberType().trim().toUpperCase();
        HmoPlan plan = plans.findById(body.planId()).orElseThrow(() -> ApiException.badRequest("Choose a plan."));
        if (!plan.isActive()) {
            throw ApiException.badRequest(plan.getName() + " is no longer offered. Choose an active plan.");
        }
        LocalDate start = AppTime.parseDate(body.startDate(), "startDate");
        LocalDate today = AppTime.today();

        HmoSubscription s = new HmoSubscription(plan.getId(), type);
        String payerName;
        Long payerUserId;
        if (HmoSubscription.ORGANISATION.equals(type)) {
            if (body.organisationId() == null) {
                throw ApiException.badRequest("Choose an approved organisation.");
            }
            HmoOrganisation org = organisations.findById(body.organisationId())
                .orElseThrow(() -> ApiException.badRequest("Organisation not found."));
            if (!HmoOrganisation.APPROVED.equals(org.getStatus())) {
                throw ApiException.badRequest(org.getCompanyName() + " hasn't been approved yet.");
            }
            if (subscriptions.existsByOrganisationIdAndStatusInAndExpiryDateGreaterThanEqual(org.getId(), LIVE, today)) {
                throw ApiException.conflict(org.getCompanyName() + " already has a current subscription. Renew it instead.");
            }
            s.setOrganisationId(org.getId());
            s.setMemberCount(body.memberCount() == null ? 1 : body.memberCount());
            payerName = org.getCompanyName();
            payerUserId = org.getAdminUserId();
        } else if (HmoSubscription.INDIVIDUAL.equals(type)) {
            if (body.patientId() == null) {
                throw ApiException.badRequest("Choose a patient.");
            }
            User patient = users.findById(body.patientId()).filter(u -> u.getRole() == Role.PATIENT)
                .orElseThrow(() -> ApiException.badRequest("Patient not found."));
            if (subscriptions.existsByPatientIdAndStatusInAndExpiryDateGreaterThanEqual(patient.getId(), LIVE, today)) {
                throw ApiException.conflict(patient.getFullName() + " already has a current subscription. Renew it instead.");
            }
            s.setPatientId(patient.getId());
            s.setMemberCount(1);
            payerName = patient.getFullName();
            payerUserId = patient.getId();
            /* They're now an HMO patient. */
            Patient profile = patients.findByUserId(patient.getId())
                .orElseGet(() -> patients.save(new Patient(patient.getId(), Patient.HMO)));
            profile.setPatientType(Patient.HMO);
        } else {
            throw ApiException.badRequest("Subscriber type must be ORGANISATION or INDIVIDUAL.");
        }

        s.setStartDate(start);
        s.setExpiryDate(expiryFor(plan, start));
        s.setCreatedById(CurrentUser.idOrNull());
        String reference = AdminUserService.blankToNull(body.paymentReference());
        BigDecimal expected = plan.getAnnualPrice().multiply(BigDecimal.valueOf(s.getMemberCount()));
        if (reference != null) {
            BigDecimal paid = body.amountPaid() != null && body.amountPaid().signum() > 0 ? body.amountPaid() : expected;
            s.setStatus(HmoSubscription.ACTIVE);
            s.setPaymentReference(reference);
            s.setAmountPaid(paid);
            s = subscriptions.save(s);
            recordPayment(payerName, payerUserId, s, paid, reference, "HMO subscription - " + plan.getName());
        } else {
            s.setStatus(HmoSubscription.PENDING_PAYMENT);
            s = subscriptions.save(s);
        }

        activity.record("HMO_SUBSCRIPTION_CREATED", payerName + " subscribed to " + plan.getName()
            + (reference == null ? " (awaiting payment)" : ""), "HMO_SUBSCRIPTION", s.getId());
        notifySubscriber(s, reference == null
            ? "Your " + plan.getName() + " subscription has been set up and is awaiting payment."
            : "Your " + plan.getName() + " cover is active until " + s.getExpiryDate() + ".");
        return views(List.of(s), today).get(0);
    }

    @Transactional
    public SubscriptionView renew(Long id, RenewBody body) {
        HmoSubscription s = subscriptions.findById(id).orElseThrow(() -> ApiException.notFound("Subscription not found."));
        HmoPlan plan = plans.findById(s.getPlanId()).orElseThrow(() -> ApiException.notFound("Plan not found."));
        LocalDate today = AppTime.today();

        if (HmoSubscription.PENDING_PAYMENT.equals(s.getStatus())) {
            /* First payment: cover starts on the agreed start date (or today if that has passed). */
            LocalDate start = s.getStartDate().isBefore(today) ? today : s.getStartDate();
            s.setStartDate(start);
            s.setExpiryDate(expiryFor(plan, start));
        } else {
            /* Renewal: the new year starts the day after expiry, or today if it has already lapsed. */
            LocalDate start = s.getExpiryDate().isBefore(today) ? today : s.getExpiryDate().plusDays(1);
            s.setStartDate(start);
            s.setExpiryDate(expiryFor(plan, start));
            s.setBenefitUsed(BigDecimal.ZERO);
        }
        s.setStatus(HmoSubscription.ACTIVE);
        s.setPaymentReference(body.paymentReference().trim());
        s.setAmountPaid(body.amountPaid());

        String payer = subscriberName(s);
        Long payerUserId = s.getPatientId() != null ? s.getPatientId()
            : s.getOrganisationId() == null ? null
            : organisations.findById(s.getOrganisationId()).map(HmoOrganisation::getAdminUserId).orElse(null);
        recordPayment(payer, payerUserId, s, body.amountPaid(), s.getPaymentReference(),
            "HMO subscription renewal - " + plan.getName());

        activity.record("HMO_SUBSCRIPTION_RENEWED", payer + "'s " + plan.getName() + " cover runs until "
            + s.getExpiryDate(), "HMO_SUBSCRIPTION", s.getId());
        notifySubscriber(s, "Your " + plan.getName() + " cover is active until " + s.getExpiryDate() + ".");
        return views(List.of(s), today).get(0);
    }

    /* ── helpers ── */

    private void recordPayment(String payerName, Long payerUserId, HmoSubscription s, BigDecimal amount,
                               String reference, String description) {
        Payment p = new Payment(payerName, amount, reference);
        p.setPayerUserId(payerUserId);
        p.setHmoSubscriptionId(s.getId());
        p.setMethod("BANK_TRANSFER");
        p.setDescription(description);
        p.setPaidAt(Instant.now());
        p.setRecordedById(CurrentUser.idOrNull());
        payments.save(p);
    }

    private void notifySubscriber(HmoSubscription s, String message) {
        if (s.getPatientId() != null) {
            notifications.notify(s.getPatientId(), "HMO cover update", message, "/patient/payments");
        } else if (s.getOrganisationId() != null) {
            organisations.findById(s.getOrganisationId()).map(HmoOrganisation::getAdminUserId).filter(Objects::nonNull)
                .ifPresent(uid -> notifications.notify(uid, "HMO cover update", message, "/hmo-dashboard/plans"));
        }
    }

    private String subscriberName(HmoSubscription s) {
        if (s.getOrganisationId() != null) {
            return organisations.findById(s.getOrganisationId()).map(HmoOrganisation::getCompanyName).orElse("Organisation");
        }
        return s.getPatientId() == null ? "Patient"
            : users.findById(s.getPatientId()).map(User::getFullName).orElse("Patient");
    }

    private List<SubscriptionView> views(List<HmoSubscription> list, LocalDate today) {
        Map<Long, HmoPlan> planById = plans.findAllById(list.stream().map(HmoSubscription::getPlanId).distinct().toList())
            .stream().collect(Collectors.toMap(HmoPlan::getId, Function.identity()));
        Map<Long, String> orgNames = organisations.findAllById(list.stream().map(HmoSubscription::getOrganisationId)
                .filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(HmoOrganisation::getId, HmoOrganisation::getCompanyName));
        Map<Long, String> patientNames = users.findAllById(list.stream().map(HmoSubscription::getPatientId)
                .filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(User::getId, User::getFullName));

        return list.stream().map(s -> {
            HmoPlan p = planById.get(s.getPlanId());
            BigDecimal limit = p == null ? BigDecimal.ZERO : p.getBenefitLimit();
            String name = s.getOrganisationId() != null ? orgNames.get(s.getOrganisationId()) : patientNames.get(s.getPatientId());
            return new SubscriptionView(s.getId(), s.getSubscriberType(), name, s.getOrganisationId(), s.getPatientId(),
                s.getPlanId(), p == null ? null : p.getName(), p == null ? null : p.getAnnualPrice(), limit,
                s.getBenefitUsed(), limit.subtract(s.getBenefitUsed()).max(BigDecimal.ZERO), s.getMemberCount(),
                s.getStartDate(), s.getExpiryDate(), s.effectiveStatus(today), s.getPaymentReference(),
                s.getAmountPaid(), s.getCreatedAt());
        }).toList();
    }

    private static LocalDate expiryFor(HmoPlan plan, LocalDate start) {
        return start.plusMonths(plan.getDurationMonths()).minusDays(1);
    }

    private static void applyPlan(HmoPlan p, PlanBody body) {
        p.setName(body.name().trim());
        p.setAnnualPrice(body.annualPrice());
        p.setBenefitLimit(body.benefitLimit());
        p.setDurationMonths(body.durationMonths() == null ? 12 : body.durationMonths());
        p.setDescription(AdminUserService.blankToNull(body.description()));
        p.setCoveredServices(String.join(",", body.coveredServices().stream().distinct().toList()));
        p.setActive(body.active() == null || body.active());
    }

    private static PlanView planView(HmoPlan p, long subscribers) {
        return new PlanView(p.getId(), p.getName(), p.getAnnualPrice(), p.getBenefitLimit(), p.getDurationMonths(),
            p.getDescription(), p.coveredServiceList(), p.isActive(), subscribers, p.getCreatedAt());
    }
}
