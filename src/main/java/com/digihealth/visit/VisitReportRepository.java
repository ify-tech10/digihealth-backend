package com.digihealth.visit;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitReportRepository extends JpaRepository<VisitReport, Long> {

    List<VisitReport> findByProviderIdOrderByVisitDateDescCreatedAtDesc(Long providerId);

    List<VisitReport> findByStatusOrderByCreatedAtDesc(String status);

    List<VisitReport> findByPatientIdAndStatusOrderByVisitDateDesc(Long patientId, String status);

    boolean existsByVisitId(Long visitId);

    List<VisitReport> findByVisitIdIn(java.util.Collection<Long> visitIds);
}
