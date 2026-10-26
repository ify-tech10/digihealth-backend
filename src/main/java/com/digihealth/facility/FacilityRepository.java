package com.digihealth.facility;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityRepository extends JpaRepository<Facility, Long> {

    List<Facility> findByKindAndDeletedAtIsNullOrderByNameAsc(FacilityKind kind);

    Optional<Facility> findByIdAndKindAndDeletedAtIsNull(Long id, FacilityKind kind);

    /** The facility a signed-in HOSPITAL_ADMIN / PHARMACY_ADMIN / LAB_ADMIN belongs to. */
    Optional<Facility> findByAdminUserIdAndDeletedAtIsNull(Long adminUserId);

    boolean existsByKindAndDeletedAtIsNullAndNameIgnoreCase(FacilityKind kind, String name);
}
