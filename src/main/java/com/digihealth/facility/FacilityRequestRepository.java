package com.digihealth.facility;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityRequestRepository extends JpaRepository<FacilityRequest, Long> {

    List<FacilityRequest> findByFacilityIdOrderByCreatedAtDesc(Long facilityId);

    List<FacilityRequest> findByFacilityIdAndStatusOrderByCreatedAtDesc(Long facilityId, String status);

    Optional<FacilityRequest> findByIdAndFacilityId(Long id, Long facilityId);

    boolean existsByReference(String reference);
}
