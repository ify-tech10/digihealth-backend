package com.digihealth.hmo;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HmoPlanChangeRequestRepository extends JpaRepository<HmoPlanChangeRequest, Long> {

    boolean existsByOrganisationIdAndStatus(Long organisationId, String status);
}
