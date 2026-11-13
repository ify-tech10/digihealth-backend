package com.digihealth.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A claim DiGi sends to an insurer for care given to a company's staff. */
@Entity
@Table(name = "hmo_claims")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HmoClaim extends BaseEntity {

    @Column(name = "claim_number", nullable = false)
    private String claimNumber;

    @Column(name = "organisation_name", nullable = false)
    private String organisationName;

    @Column(name = "organisation_id")
    private Long organisationId;

    @Column(name = "hmo_provider", nullable = false)
    private String hmoProvider;

    @Column(name = "service_period", nullable = false)
    private String servicePeriod;

    @Column
    private Integer members;

    @Column(name = "amount_claimed", nullable = false)
    private BigDecimal amountClaimed;

    @Column(name = "amount_received")
    private BigDecimal amountReceived;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "submitted_at", nullable = false)
    private LocalDate submittedAt;

    @Column
    private String notes;

    @Column(name = "follow_up_count", nullable = false)
    private int followUpCount;

    @Column(name = "last_follow_up_at")
    private Instant lastFollowUpAt;

    @Column(name = "last_follow_up_note")
    private String lastFollowUpNote;

    @Column(name = "paid_at")
    private LocalDate paidAt;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "created_by_id")
    private Long createdById;

    public HmoClaim(String claimNumber, String organisationName, String hmoProvider) {
        this.claimNumber = claimNumber;
        this.organisationName = organisationName;
        this.hmoProvider = hmoProvider;
    }
}
