package com.digihealth.provider;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A care provider; starts as a PENDING application. */
@Entity
@Table(name = "providers")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Provider extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(nullable = false)
    private String address;

    @Column
    private String gender;

    @Column(name = "service_type", nullable = false)
    private String serviceType;

    @Column(name = "provider_type")
    private String providerType;

    @Column(name = "years_of_experience", nullable = false)
    private String yearsOfExperience;

    @Column
    private String qualification;

    @Column(name = "employment_status")
    private String employmentStatus;

    @Column
    private String specialisations;

    @Column(name = "professional_summary")
    private String professionalSummary;

    @Column(name = "availability_type", nullable = false)
    private String availabilityType;

    @Column(name = "preferred_hours")
    private String preferredHours;

    @Column(name = "location_area")
    private String locationArea;

    @Column(name = "start_date")
    private String startDate;

    @Column(name = "additional_info")
    private String additionalInfo;

    @Column(name = "ref1_full_name")
    private String ref1FullName;

    @Column(name = "ref1_relationship")
    private String ref1Relationship;

    @Column(name = "ref1_phone")
    private String ref1Phone;

    @Column(name = "ref1_email")
    private String ref1Email;

    @Column(name = "ref2_full_name")
    private String ref2FullName;

    @Column(name = "ref2_relationship")
    private String ref2Relationship;

    @Column(name = "ref2_phone")
    private String ref2Phone;

    @Column(name = "ref2_email")
    private String ref2Email;

    @Column(name = "cv_file_id", nullable = false)
    private Long cvFileId;

    @Column(name = "certificate_file_id")
    private Long certificateFileId;

    @Column(name = "government_id_file_id")
    private Long governmentIdFileId;

    @Column(name = "license_file_id")
    private Long licenseFileId;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note")
    private String reviewNote;

    public Provider(String fullName, String email, String phoneNumber) {
        this.fullName = fullName;
        this.email = email;
        this.phoneNumber = phoneNumber;
    }
}
