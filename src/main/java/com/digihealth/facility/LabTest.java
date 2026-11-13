package com.digihealth.facility;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A test on a lab centre's menu, with the price DiGi sees when ordering. */
@Entity
@Table(name = "lab_tests")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LabTest extends BaseEntity {

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(nullable = false)
    private String name;

    @Column
    private String code;

    @Column
    private String category;

    @Column(name = "sample_type")
    private String sampleType;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "turnaround_hours", nullable = false)
    private int turnaroundHours;

    @Column(nullable = false)
    private boolean fasting;

    @Column(name = "home_collection", nullable = false)
    private boolean homeCollection = true;

    @Column(nullable = false)
    private boolean active = true;

    @Column
    private String preparation;

    public LabTest(Long facilityId) {
        this.facilityId = facilityId;
    }
}
