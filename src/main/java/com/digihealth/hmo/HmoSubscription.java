package com.digihealth.hmo;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A company or a single patient on a plan for one year. Expired = past expiry_date. */
@Entity
@Table(name = "hmo_subscriptions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HmoSubscription extends BaseEntity {

    public static final String ORGANISATION = "ORGANISATION";
    public static final String INDIVIDUAL = "INDIVIDUAL";
    public static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    public static final String ACTIVE = "ACTIVE";
    public static final String EXPIRED = "EXPIRED";   // shown only, never stored

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "subscriber_type", nullable = false)
    private String subscriberType;

    @Column(name = "organisation_id")
    private Long organisationId;

    /** users.id of the patient (INDIVIDUAL) */
    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "member_count", nullable = false)
    private int memberCount = 1;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(nullable = false)
    private String status;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "amount_paid")
    private BigDecimal amountPaid;

    @Column(name = "benefit_used", nullable = false)
    private BigDecimal benefitUsed = BigDecimal.ZERO;

    @Column(name = "created_by_id")
    private Long createdById;

    public HmoSubscription(Long planId, String subscriberType) {
        this.planId = planId;
        this.subscriberType = subscriberType;
    }

    /** What the page shows: an ACTIVE subscription past its expiry date is EXPIRED. */
    public String effectiveStatus(LocalDate today) {
        return ACTIVE.equals(status) && expiryDate.isBefore(today) ? EXPIRED : status;
    }
}
