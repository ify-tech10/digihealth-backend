package com.digihealth.labportal;

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

/**
 * A request from the medical team to DiGi's lab scientists or pharmacists.
 * NEW -> ACCEPTED -> IN_PROGRESS (sample collected / out for delivery) -> COMPLETED, or DECLINED.
 */
@Entity
@Table(name = "service_requests")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceRequest extends BaseEntity {

    @Column(nullable = false)
    private String kind;

    @Column(name = "request_number", nullable = false)
    private String requestNumber;

    @Column(nullable = false)
    private String status = NEW;

    @Column(nullable = false)
    private String priority = "ROUTINE";

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "patient_name", nullable = false)
    private String patientName;

    @Column(name = "patient_phone")
    private String patientPhone;

    @Column
    private String address;

    @Column(name = "location_area")
    private String locationArea;

    @Column(name = "patient_type")
    private String patientType;

    @Column(name = "requested_by_id")
    private Long requestedById;

    @Column(name = "requested_by_name")
    private String requestedByName;

    @Column(name = "requested_by_role")
    private String requestedByRole;

    @Column
    private String notes;

    @Column(nullable = false)
    private String lines;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Column(name = "handled_by_id")
    private Long handledById;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accept_note")
    private String acceptNote;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Column(name = "decline_reason")
    private String declineReason;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "start_note")
    private String startNote;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "result_summary")
    private String resultSummary;

    @Column(nullable = false)
    private boolean abnormal;

    @Column(name = "result_file_id")
    private Long resultFileId;

    @Column(name = "received_by")
    private String receivedBy;

    @Column(name = "delivery_note")
    private String deliveryNote;

    @Column(name = "proof_file_id")
    private Long proofFileId;

    public static final String NEW = "NEW";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String DECLINED = "DECLINED";

    public ServiceRequest(String kind, String requestNumber, String patientName, String lines) {
        this.kind = kind;
        this.requestNumber = requestNumber;
        this.patientName = patientName;
        this.lines = lines;
    }

    public boolean isOpen() {
        return NEW.equals(status) || ACCEPTED.equals(status) || IN_PROGRESS.equals(status);
    }
}
