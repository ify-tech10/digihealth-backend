package com.digihealth.facility;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Work DiGi's care team sends to a partner facility:
 *   hospital: referral     NEW -> ACCEPTED -> ADMITTED   -> DISCHARGED  (or DECLINED)
 *   pharmacy: drug order   NEW -> ACCEPTED -> DISPATCHED -> DELIVERED   (or DECLINED)
 *   lab:      test request NEW -> ACCEPTED -> COLLECTED  -> RESULTED    (or DECLINED)
 * Patient details, vitals and the item / test lines are JSON text.
 */
@Entity
@Table(name = "facility_requests")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FacilityRequest extends BaseEntity {

    public static final String NEW = "NEW";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String DECLINED = "DECLINED";
    public static final String ADMITTED = "ADMITTED";
    public static final String DISCHARGED = "DISCHARGED";
    public static final String DISPATCHED = "DISPATCHED";
    public static final String DELIVERED = "DELIVERED";
    public static final String COLLECTED = "COLLECTED";
    public static final String RESULTED = "RESULTED";

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FacilityKind kind;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(nullable = false)
    private String status = NEW;

    @Column(nullable = false)
    private String urgency = "ROUTINE";

    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "patient_name", nullable = false)
    private String patientName;

    @Column(name = "patient_info")
    private String patientInfo;

    @Column(name = "reason")
    private String reason;

    @Column(name = "diagnosis")
    private String diagnosis;

    @Column(name = "prior_care_summary")
    private String priorCareSummary;

    @Column(name = "vitals")
    private String vitals;

    @Column(name = "medications")
    private String medications;

    @Column(name = "allergies")
    private String allergies;

    @Column(name = "clinical_notes")
    private String clinicalNotes;

    @Column(name = "lines")
    private String lines;

    @Column(name = "attachment_file_id")
    private Long attachmentFileId;

    @Column(name = "requested_by_id")
    private Long requestedById;

    @Column(name = "requested_by_name")
    private String requestedByName;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accept_note")
    private String acceptNote;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Column(name = "decline_reason")
    private String declineReason;

    @Column(name = "expected_arrival")
    private Instant expectedArrival;

    @Column(name = "ward")
    private String ward;

    @Column(name = "admitted_at")
    private Instant admittedAt;

    @Column(name = "discharged_at")
    private Instant dischargedAt;

    @Column(name = "discharge_summary")
    private String dischargeSummary;

    @Column(name = "discharge_outcome")
    private String dischargeOutcome;

    @Column(name = "follow_up")
    private String followUp;

    @Column(name = "discharge_file_id")
    private Long dischargeFileId;

    @Column(name = "dispatch_at")
    private Instant dispatchAt;

    @Column(name = "rider")
    private String rider;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "received_by")
    private String receivedBy;

    @Column(name = "delivery_note")
    private String deliveryNote;

    @Column(name = "proof_file_id")
    private Long proofFileId;

    @Column(name = "collection_type")
    private String collectionType;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "turnaround_hours")
    private Integer turnaroundHours;

    @Column(name = "collected_at")
    private Instant collectedAt;

    @Column(name = "collected_by")
    private String collectedBy;

    @Column(name = "sample_id")
    private String sampleId;

    @Column(name = "patient_fasted")
    private Boolean patientFasted;

    @Column(name = "collection_note")
    private String collectionNote;

    @Column(name = "resulted_at")
    private Instant resultedAt;

    @Column(name = "critical", nullable = false)
    private boolean critical;

    @Column(name = "result_comment")
    private String resultComment;

    @Column(name = "report_file_id")
    private Long reportFileId;

    public FacilityRequest(Long facilityId, FacilityKind kind, String reference, String patientName) {
        this.facilityId = facilityId;
        this.kind = kind;
        this.reference = reference;
        this.patientName = patientName;
    }

    /** Finished: discharged, delivered, resulted or declined. */
    public boolean isDone() {
        return DISCHARGED.equals(status) || DELIVERED.equals(status) || RESULTED.equals(status) || DECLINED.equals(status);
    }
}
