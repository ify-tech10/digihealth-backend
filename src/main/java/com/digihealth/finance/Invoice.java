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

/** A bill from DiGi to a patient or a company. Payments recorded against it make it PARTIAL / PAID. */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invoice extends BaseEntity {

    @Column(name = "invoice_number", nullable = false)
    private String invoiceNumber;

    @Column(name = "client_type", nullable = false)
    private String clientType;

    @Column(name = "client_name", nullable = false)
    private String clientName;

    @Column(name = "client_email")
    private String clientEmail;

    @Column(name = "patient_user_id")
    private Long patientUserId;

    @Column(name = "organisation_id")
    private Long organisationId;

    @Column(name = "care_request_id")
    private Long careRequestId;

    @Column(nullable = false)
    private String service;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "vat_percent", nullable = false)
    private BigDecimal vatPercent = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column
    private String notes;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "reminder_count", nullable = false)
    private int reminderCount;

    @Column(name = "last_reminded_at")
    private Instant lastRemindedAt;

    @Column(name = "created_by_id")
    private Long createdById;

    public static final String PENDING = "PENDING";
    public static final String PARTIAL = "PARTIAL";
    public static final String PAID = "PAID";
    public static final String CANCELLED = "CANCELLED";

    public Invoice(String invoiceNumber, String clientType, String clientName) {
        this.invoiceNumber = invoiceNumber;
        this.clientType = clientType;
        this.clientName = clientName;
    }

    public BigDecimal balance() {
        return CANCELLED.equals(status) ? BigDecimal.ZERO : totalAmount.subtract(amountPaid).max(BigDecimal.ZERO);
    }

    /** Adds a payment and moves the status on. */
    public void applyPayment(BigDecimal paid, Instant when) {
        amountPaid = amountPaid.add(paid);
        if (amountPaid.compareTo(totalAmount) >= 0) {
            status = PAID;
            paidAt = when;
        } else {
            status = PARTIAL;
        }
    }
}
