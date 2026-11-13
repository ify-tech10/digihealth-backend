package com.digihealth.labportal;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One change to a drug's stock: manual adjustment or dispensing on a request. */
@Entity
@Table(name = "stock_movements")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMovement extends BaseEntity {

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private int change;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Column(nullable = false)
    private String reason;

    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "by_id")
    private Long byId;

    public StockMovement(Long itemId, int change, int balanceAfter, String reason, Long requestId, Long byId) {
        this.itemId = itemId;
        this.change = change;
        this.balanceAfter = balanceAfter;
        this.reason = reason;
        this.requestId = requestId;
        this.byId = byId;
    }
}
