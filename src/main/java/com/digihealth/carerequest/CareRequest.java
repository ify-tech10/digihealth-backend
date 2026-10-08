package com.digihealth.carerequest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "care_requests")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CareRequest extends BaseEntity {

    public static final String STATUS_NEW = "NEW";

    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column
    private String address;

    @Column(name = "location_area")
    private String locationArea;

    @Column(name = "service_needed", nullable = false)
    private String serviceNeeded;

    @Column
    private String description;

    @Column(name = "preferred_contact_time")
    private String preferredContactTime;

    @Column(nullable = false)
    private String status = STATUS_NEW;

    /** WEBSITE, CUSTOMER_CARE or PORTAL */
    @Column(nullable = false)
    private String source;

    @Column(name = "created_by_id")
    private Long createdById;

    public CareRequest(String fullName, String email, String phoneNumber, String serviceNeeded, String source) {
        this.fullName = fullName;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.serviceNeeded = serviceNeeded;
        this.source = source;
    }
}
