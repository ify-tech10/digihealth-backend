package com.digihealth.visit;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitRepository extends JpaRepository<Visit, Long> {

    List<Visit> findByScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(Instant from, Instant to);

    List<Visit> findByProviderIdAndScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
        Long providerId, Instant from, Instant to);

    List<Visit> findByPatientIdOrderByScheduledAtDesc(Long patientId);

    List<Visit> findByCareRequestIdOrderByScheduledAtAsc(Long careRequestId);
}
