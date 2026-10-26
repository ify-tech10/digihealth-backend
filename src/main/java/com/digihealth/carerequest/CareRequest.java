package com.digihealth.carerequest;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "care_requests")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CareRequest extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String PENDING_CLOSURE = "PENDING_CLOSURE";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column
    private String address;

    @Column(name = "location_area")
    private String locationArea;

    @Column(name = "service_needed", nullable = false)
    private String serviceNeeded;

    @Column
    private String description;

    @Column(name = "preferred_contact_time")
    private String preferredContactTime;

    @Column(nullable = false)
    private String status = PENDING;

    /** WEBSITE, CUSTOMER_CARE or PORTAL */
    @Column(nullable = false)
    private String source;

    @Column(name = "created_by_id")
    private Long createdById;

    /** providers.id */
    @Column(name = "assigned_provider_id")
    private Long assignedProviderId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "assigned_by_id")
    private Long assignedById;

    @Column(name = "admin_note")
    private String adminNote;

    @Column(name = "closure_note")
    private String closureNote;

    @Column(name = "closure_requested_at")
    private Instant closureRequestedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by_id")
    private Long closedById;

    @Column(name = "closure_review_note")
    private String closureReviewNote;

    public CareRequest(String fullName, String email, String phoneNumber, String serviceNeeded, String source) {
        this.fullName = fullName;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.serviceNeeded = serviceNeeded;
        this.source = source;
    }
}
