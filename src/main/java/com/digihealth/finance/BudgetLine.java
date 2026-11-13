package com.digihealth.finance;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The amount budgeted for one category in one month. */
@Entity
@Table(name = "budget_lines")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BudgetLine extends BaseEntity {

    @Column(nullable = false)
    private String month;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private BigDecimal budgeted;

    public BudgetLine(String month, String category, BigDecimal budgeted) {
        this.month = month;
        this.category = category;
        this.budgeted = budgeted;
    }
}
