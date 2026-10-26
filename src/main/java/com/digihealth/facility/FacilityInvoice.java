package com.digihealth.facility;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A bill from a hospital / pharmacy / lab to DiGi: Admin approves, Finance pays. */
@Entity
@Table(name = "facility_invoices")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FacilityInvoice extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String PAID = "PAID";

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "invoice_number", nullable = false, unique = true)
    private String invoiceNumber;

    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "patient_name")
    private String patientName;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column
    private String notes;

    @Column(name = "attachment_file_id")
    private Long attachmentFileId;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "review_note")
    private String reviewNote;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "payment_reference")
    private String paymentReference;

    public FacilityInvoice(Long facilityId, String invoiceNumber, BigDecimal totalAmount) {
        this.facilityId = facilityId;
        this.invoiceNumber = invoiceNumber;
        this.totalAmount = totalAmount;
    }
}
