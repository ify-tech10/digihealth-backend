package com.digihealth.patient;

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

/** Patient details for a PATIENT user (one row each). */
@Entity
@Table(name = "patients")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Patient extends BaseEntity {

    public static final String STANDARD = "STANDARD";
    public static final String HMO = "HMO";

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "patient_type", nullable = false)
    private String patientType = STANDARD;

    @Column(name = "hmo_organisation_id")
    private Long hmoOrganisationId;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column
    private String gender;

    @Column
    private String address;

    @Column(name = "area_lga")
    private String areaLga;

    @Column
    private String landmark;

    @Column(name = "next_of_kin_name")
    private String nextOfKinName;

    @Column(name = "next_of_kin_relationship")
    private String nextOfKinRelationship;

    @Column(name = "next_of_kin_phone")
    private String nextOfKinPhone;

    @Column(name = "blood_group")
    private String bloodGroup;

    @Column
    private String genotype;

    /** Comma-separated lists. */
    @Column
    private String allergies;

    @Column
    private String conditions;

    @Column(name = "current_medications")
    private String currentMedications;

    @Column(name = "primary_doctor")
    private String primaryDoctor;

    @Column(name = "notify_sms", nullable = false)
    private boolean notifySms = true;

    @Column(name = "notify_whatsapp", nullable = false)
    private boolean notifyWhatsapp = true;

    @Column(name = "notify_email", nullable = false)
    private boolean notifyEmail = true;

    @Column(name = "consent_signed_at")
    private Instant consentSignedAt;

    public Patient(Long userId, String patientType) {
        this.userId = userId;
        this.patientType = patientType == null ? STANDARD : patientType;
    }
}
