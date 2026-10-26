package com.digihealth.facility;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "facilities")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Facility extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FacilityKind kind;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    @Column(nullable = false)
    private String address;

    @Column(name = "location_area", nullable = false)
    private String locationArea;

    @Column(name = "contact_person", nullable = false)
    private String contactPerson;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "admin_user_id")
    private Long adminUserId;

    @Column(name = "opening_hours")
    private String openingHours;

    @Column(nullable = false)
    private boolean accepting = true;

    @Column(name = "beds_available")
    private Integer bedsAvailable;

    @Column(name = "delivery_areas")
    private String deliveryAreas;

    @Column(name = "home_collection", nullable = false)
    private boolean homeCollection;

    @Column(name = "collection_areas")
    private String collectionAreas;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "account_number")
    private String accountNumber;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public Facility(FacilityKind kind, String name) {
        this.kind = kind;
        this.name = name;
    }
}
