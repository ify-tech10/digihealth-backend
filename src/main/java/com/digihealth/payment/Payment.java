package com.digihealth.payment;

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

/** Money received by DiGi Health. */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    public static final String PAID = "PAID";

    @Column(name = "payer_name", nullable = false)
    private String payerName;

    @Column(name = "payer_user_id")
    private Long payerUserId;

    @Column(name = "hmo_subscription_id")
    private Long hmoSubscriptionId;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column
    private String method;

    @Column(nullable = false)
    private String reference;

    @Column
    private String description;

    @Column(nullable = false)
    private String status = PAID;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "recorded_by_id")
    private Long recordedById;

    public Payment(String payerName, BigDecimal amount, String reference) {
        this.payerName = payerName;
        this.amount = amount;
        this.reference = reference;
    }
}
