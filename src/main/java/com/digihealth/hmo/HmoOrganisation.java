package com.digihealth.hmo;

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

/** A company buying HMO cover for its staff; starts as a PENDING application. */
@Entity
@Table(name = "hmo_organisations")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HmoOrganisation extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";

    @Column(name = "admin_user_id")
    private Long adminUserId;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "company_size", nullable = false)
    private String companySize;

    @Column(nullable = false)
    private String email;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(name = "company_address", nullable = false)
    private String companyAddress;

    @Column(name = "hr_contact_name")
    private String hrContactName;

    @Column
    private String industry;

    @Column(name = "start_date")
    private LocalDate startDate;

    /** PUBLIC or RELATIONSHIP_MANAGER */
    @Column(name = "created_by", nullable = false)
    private String createdBy = "PUBLIC";

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note")
    private String reviewNote;

    public HmoOrganisation(String companyName, String companySize, String email, String phoneNumber, String companyAddress) {
        this.companyName = companyName;
        this.companySize = companySize;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.companyAddress = companyAddress;
    }
}
