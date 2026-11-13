package com.digihealth.facility;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LabTestRepository extends JpaRepository<LabTest, Long> {

    List<LabTest> findByFacilityIdOrderByNameAsc(Long facilityId);

    Optional<LabTest> findByIdAndFacilityId(Long id, Long facilityId);

    boolean existsByFacilityIdAndNameIgnoreCase(Long facilityId, String name);

    boolean existsByFacilityIdAndNameIgnoreCaseAndIdNot(Long facilityId, String name, Long id);
}
