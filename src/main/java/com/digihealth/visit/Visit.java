package com.digihealth.visit;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One home visit by a provider. Created by the provider (Phase 7) or from a booking. */
@Entity
@Table(name = "visits")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Visit extends BaseEntity {

    public static final String SCHEDULED = "SCHEDULED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String MISSED = "MISSED";

    @Column(name = "care_request_id")
    private Long careRequestId;

    /** providers.id */
    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    /** users.id of the patient */
    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "service_type")
    private String serviceType;

    @Column
    private String title;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column
    private String address;

    @Column(name = "location_area")
    private String locationArea;

    @Column(nullable = false)
    private String status = SCHEDULED;

    @Column(name = "check_in_at")
    private Instant checkInAt;

    @Column(name = "check_out_at")
    private Instant checkOutAt;

    @Column
    private String notes;

    @Column(name = "created_by_id")
    private Long createdById;

    public Visit(Long providerId, Instant scheduledAt) {
        this.providerId = providerId;
        this.scheduledAt = scheduledAt;
    }
}
