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

/** A request for funds from the CNO / medical director / finance manager. */
@Entity
@Table(name = "financial_requests")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialRequest extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "needed_by")
    private LocalDate neededBy;

    @Column
    private String description;

    @Column(name = "attachment_file_id")
    private Long attachmentFileId;

    @Column(name = "requested_by_id")
    private Long requestedById;

    @Column(name = "requested_by_name")
    private String requestedByName;

    @Column(name = "requested_by_role")
    private String requestedByRole;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "review_note")
    private String reviewNote;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    public FinancialRequest(String title, String category, BigDecimal amount) {
        this.title = title;
        this.category = category;
        this.amount = amount;
    }
}
