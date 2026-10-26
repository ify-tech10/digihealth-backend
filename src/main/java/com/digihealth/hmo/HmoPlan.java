package com.digihealth.hmo;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An HMO plan. Every plan has a yearly price and a benefit limit. */
@Entity
@Table(name = "hmo_plans")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HmoPlan extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "annual_price", nullable = false)
    private BigDecimal annualPrice;

    @Column(name = "benefit_limit", nullable = false)
    private BigDecimal benefitLimit;

    @Column(name = "duration_months", nullable = false)
    private int durationMonths = 12;

    @Column
    private String description;

    /** Comma-separated service codes. */
    @Column(name = "covered_services", nullable = false)
    private String coveredServices;

    @Column(nullable = false)
    private boolean active = true;

    public HmoPlan(String name) {
        this.name = name;
    }

    public List<String> coveredServiceList() {
        return coveredServices == null || coveredServices.isBlank()
            ? List.of()
            : Arrays.stream(coveredServices.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
