package com.digihealth.labportal;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A test or drug on DiGi's price list. Drugs carry stock; tests don't. */
@Entity
@Table(name = "catalogue_items")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogueItem extends BaseEntity {

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String name;

    @Column
    private String code;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column
    private String strength;

    @Column
    private String form;

    @Column(name = "sample_type")
    private String sampleType;

    @Column(name = "turnaround_hours")
    private Integer turnaroundHours;

    @Column
    private Integer stock;

    @Column(name = "reorder_level", nullable = false)
    private int reorderLevel = 10;

    @Column(nullable = false)
    private boolean active = true;

    public CatalogueItem(String kind, String name) {
        this.kind = kind;
        this.name = name;
    }
}
