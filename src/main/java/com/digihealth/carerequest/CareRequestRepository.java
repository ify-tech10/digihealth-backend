package com.digihealth.carerequest;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CareRequestRepository extends JpaRepository<CareRequest, Long> {

    List<CareRequest> findByPatientIdOrderByCreatedAtDesc(Long patientId);

    List<CareRequest> findByStatusOrderByCreatedAtDesc(String status);

    List<CareRequest> findAllByOrderByCreatedAtDesc();

    long countByStatus(String status);
}
