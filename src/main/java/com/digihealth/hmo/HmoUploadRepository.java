package com.digihealth.hmo;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HmoUploadRepository extends JpaRepository<HmoUpload, Long> {

    List<HmoUpload> findByOrganisationIdOrderByCreatedAtDesc(Long organisationId);

    long countByOrganisationIdAndCreatedAtGreaterThanEqual(Long organisationId, Instant since);
}
