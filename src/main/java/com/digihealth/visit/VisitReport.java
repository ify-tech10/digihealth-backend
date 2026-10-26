package com.digihealth.visit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

/** What the provider recorded at a visit; reviewed by clinical leadership. */
@Entity
@Table(name = "visit_reports")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitReport extends BaseEntity {

    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED = "APPROVED";
    public static final String RETURNED = "RETURNED";

    @Column(name = "care_request_id", nullable = false)
    private Long careRequestId;

    @Column(name = "visit_id", unique = true)
    private Long visitId;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    /** Comma-separated activity codes. */
    @Column(nullable = false)
    private String activities;

    @Column(name = "patient_condition", nullable = false)
    private String patientCondition;

    @Column(name = "medication_given")
    private String medicationGiven;

    @Column(nullable = false)
    private String notes;

    @Column(name = "next_steps")
    private String nextSteps;

    @Column(name = "blood_pressure")
    private String bloodPressure;

    @Column
    private Integer pulse;

    @Column
    private BigDecimal temperature;

    @Column
    private Integer spo2;

    @Column(name = "blood_sugar")
    private BigDecimal bloodSugar;

    @Column(name = "respiratory_rate")
    private Integer respiratoryRate;

    @Column(nullable = false)
    private String status = SUBMITTED;

    @Column(name = "review_note")
    private String reviewNote;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    public VisitReport(Long careRequestId, Long providerId, LocalDate visitDate) {
        this.careRequestId = careRequestId;
        this.providerId = providerId;
        this.visitDate = visitDate;
    }

    public List<String> activityList() {
        return activities == null ? List.of()
            : Arrays.stream(activities.split(",")).map(String::trim).filter(a -> !a.isEmpty()).toList();
    }
}
