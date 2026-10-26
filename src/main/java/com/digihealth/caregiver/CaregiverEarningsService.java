package com.digihealth.caregiver;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.carerequest.CareRequest;
import com.digihealth.carerequest.CareRequestRepository;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.notification.NotificationService;
import com.digihealth.provider.Provider;
import com.digihealth.user.Role;
import com.digihealth.visit.Visit;
import com.digihealth.visit.VisitRepository;

/*
 * How a provider is paid. The provider proposes a structure and rate;
 * it stays PENDING until Finance / Admin approve it (Phase 12).
 * This month's estimate only counts once the rate is approved.
 */
@Service
public class CaregiverEarningsService {

    private static final Set<String> STRUCTURES = Set.of("PER_REQUEST", "WEEKLY", "MONTHLY");

    private final CaregiverSupport support;
    private final CareRequestRepository requests;
    private final VisitRepository visits;
    private final ProviderPayoutRepository payouts;
    private final NotificationService notifications;
    private final ActivityService activity;

    public CaregiverEarningsService(CaregiverSupport support, CareRequestRepository requests, VisitRepository visits,
                                    ProviderPayoutRepository payouts, NotificationService notifications,
                                    ActivityService activity) {
        this.support = support;
        this.requests = requests;
        this.visits = visits;
        this.payouts = payouts;
        this.notifications = notifications;
        this.activity = activity;
    }

    public record StructureBody(@NotBlank(message = "Choose how you're paid") String structure,
                                @NotNull(message = "Enter your rate") @DecimalMin(value = "1", message = "Enter your rate") BigDecimal rate) {
    }

    public record StructureView(String structure, BigDecimal rate, BigDecimal ratePerVisit, String status,
                                Instant updatedAt) {
    }

    public record Summary(BigDecimal total, BigDecimal thisMonthEarnings, long completedVisits, long completedRequests,
                          String structure, BigDecimal rate, BigDecimal ratePerVisit, BigDecimal bonuses,
                          String structureStatus) {
    }

    public record PayoutRow(Long id, LocalDate periodStart, LocalDate periodEnd, int completedVisits, String structure,
                            BigDecimal amount, String status, String reference, Instant paidAt) {
    }

    @Transactional(readOnly = true)
    public StructureView structure() {
        Provider me = support.me();
        return new StructureView(me.getEarningStructure(), me.getEarningRate(),
            "PER_REQUEST".equals(me.getEarningStructure()) ? me.getEarningRate() : null,
            me.getEarningStatus(), me.getEarningUpdatedAt());
    }

    @Transactional
    public StructureView setStructure(StructureBody body) {
        Provider me = support.me();
        String structure = body.structure().trim().toUpperCase();
        if (!STRUCTURES.contains(structure)) {
            throw ApiException.badRequest("Choose per request, weekly or monthly.");
        }
        me.setEarningStructure(structure);
        me.setEarningRate(body.rate());
        me.setEarningStatus("PENDING");
        me.setEarningUpdatedAt(Instant.now());

        String msg = me.getFullName() + " proposed " + structure.toLowerCase().replace('_', ' ') + " pay of ₦"
            + String.format("%,.0f", body.rate()) + ".";
        notifications.notifyRoles(Set.of(Role.FINANCE_MANAGER), "Earning structure to review", msg, "/finance/payroll");
        notifications.notifyRoles(Role.ADMINS, "Earning structure to review", msg, "/admin/personnel");
        activity.record("EARNING_STRUCTURE_PROPOSED", msg, "PROVIDER", me.getId());
        return structure();
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        Provider me = support.me();
        LocalDate first = AppTime.today().withDayOfMonth(1);
        Instant since = AppTime.startOf(first);
        long completedVisits = visits.countByProviderIdAndStatusAndCheckOutAtGreaterThanEqual(me.getId(), Visit.COMPLETED, since);
        long completedRequests = requests.countByAssignedProviderIdAndStatusAndClosedAtGreaterThanEqual(
            me.getId(), CareRequest.COMPLETED, since);
        BigDecimal total = monthTotal(me);
        return new Summary(total, total, completedVisits, completedRequests, me.getEarningStructure(), me.getEarningRate(),
            "PER_REQUEST".equals(me.getEarningStructure()) ? me.getEarningRate() : null, BigDecimal.ZERO,
            me.getEarningStatus());
    }

    /** Estimate for the current month, only once the rate is approved. */
    public BigDecimal monthTotal(Provider me) {
        if (!"APPROVED".equals(me.getEarningStatus()) || me.getEarningRate() == null || me.getEarningStructure() == null) {
            return null;
        }
        LocalDate today = AppTime.today();
        return switch (me.getEarningStructure()) {
            case "PER_REQUEST" -> me.getEarningRate().multiply(BigDecimal.valueOf(
                requests.countByAssignedProviderIdAndStatusAndClosedAtGreaterThanEqual(me.getId(), CareRequest.COMPLETED,
                    AppTime.startOf(today.withDayOfMonth(1)))));
            case "WEEKLY" -> me.getEarningRate().multiply(BigDecimal.valueOf((today.getDayOfMonth() + 6) / 7));
            case "MONTHLY" -> me.getEarningRate();
            default -> null;
        };
    }

    @Transactional(readOnly = true)
    public List<PayoutRow> payouts() {
        Provider me = support.me();
        return payouts.findByProviderIdOrderByPeriodEndDesc(me.getId()).stream()
            .map(p -> new PayoutRow(p.getId(), p.getPeriodStart(), p.getPeriodEnd(), p.getCompletedVisits(),
                p.getStructure(), p.getAmount(), p.getStatus(), p.getReference(), p.getPaidAt()))
            .toList();
    }
}
