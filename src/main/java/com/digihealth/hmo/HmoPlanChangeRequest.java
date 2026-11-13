package com.digihealth.hmo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A company asking DiGi to move it to another plan; Admin / the RM acts on it. */
@Entity
@Table(name = "hmo_plan_change_requests")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HmoPlanChangeRequest extends BaseEntity {

    @Column(name = "organisation_id", nullable = false)
    private Long organisationId;

    @Column(name = "current_plan_id")
    private Long currentPlanId;

    @Column(name = "requested_plan_id", nullable = false)
    private Long requestedPlanId;

    @Column
    private String note;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "requested_by_id")
    private Long requestedById;

    public HmoPlanChangeRequest(Long organisationId, Long requestedPlanId) {
        this.organisationId = organisationId;
        this.requestedPlanId = requestedPlanId;
    }
}
