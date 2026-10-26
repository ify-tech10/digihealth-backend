package com.digihealth.caregiver;

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

/** Money paid (or due) to a provider for a period. Created by Finance in Phase 12. */
@Entity
@Table(name = "provider_payouts")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProviderPayout extends BaseEntity {

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "completed_visits", nullable = false)
    private int completedVisits;

    @Column
    private String structure;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column
    private String reference;

    @Column(name = "paid_at")
    private Instant paidAt;

    public ProviderPayout(Long providerId, LocalDate periodStart, LocalDate periodEnd, BigDecimal amount) {
        this.providerId = providerId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.amount = amount;
    }
}
