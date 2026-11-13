package com.digihealth.finance;

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

/** One person's pay for a processed month. */
@Entity
@Table(name = "payroll_lines")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayrollLine extends BaseEntity {

    @Column(nullable = false)
    private String month;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "payout_id")
    private Long payoutId;

    @Column(name = "staff_name", nullable = false)
    private String staffName;

    @Column
    private String role;

    @Column(name = "pay_basis", nullable = false)
    private String payBasis;

    @Column(name = "requests_completed")
    private Integer requestsCompleted;

    @Column(name = "gross_pay", nullable = false)
    private BigDecimal grossPay;

    @Column(nullable = false)
    private BigDecimal allowances = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal deductions = BigDecimal.ZERO;

    @Column(name = "net_pay", nullable = false)
    private BigDecimal netPay;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "account_number_enc")
    private String accountNumberEnc;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column
    private String reference;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "processed_by_id")
    private Long processedById;

    public PayrollLine(String month, String staffName, String payBasis) {
        this.month = month;
        this.staffName = staffName;
        this.payBasis = payBasis;
    }
}
