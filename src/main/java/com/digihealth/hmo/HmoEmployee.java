package com.digihealth.hmo;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A company's employee on its HMO plan. The person is a PATIENT user (patient_type HMO). */
@Entity
@Table(name = "hmo_employees")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HmoEmployee extends BaseEntity {

    @Column(name = "organisation_id", nullable = false)
    private Long organisationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column
    private String department;

    @Column(name = "staff_id")
    private String staffId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "deactivated_reason")
    private String deactivatedReason;

    @Column(name = "invited_at")
    private Instant invitedAt;

    @Column(name = "upload_id")
    private Long uploadId;

    @Column(name = "created_by_id")
    private Long createdById;

    public HmoEmployee(Long organisationId, Long userId, String firstName, String lastName) {
        this.organisationId = organisationId;
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }
}
