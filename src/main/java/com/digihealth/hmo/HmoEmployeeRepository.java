package com.digihealth.hmo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HmoEmployeeRepository extends JpaRepository<HmoEmployee, Long> {

    List<HmoEmployee> findByOrganisationIdOrderByCreatedAtDesc(Long organisationId);

    Optional<HmoEmployee> findByIdAndOrganisationId(Long id, Long organisationId);

    Optional<HmoEmployee> findByOrganisationIdAndUserId(Long organisationId, Long userId);

    /** Another company's active employee with this login. */
    boolean existsByUserIdAndActiveTrueAndOrganisationIdNot(Long userId, Long organisationId);
}
