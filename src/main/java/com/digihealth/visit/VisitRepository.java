package com.digihealth.visit;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitRepository extends JpaRepository<Visit, Long> {

    List<Visit> findByScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(Instant from, Instant to);

    List<Visit> findByProviderIdAndScheduledAtGreaterThanEqualAndScheduledAtLessThanOrderByScheduledAtAsc(
        Long providerId, Instant from, Instant to);

    List<Visit> findByPatientIdOrderByScheduledAtDesc(Long patientId);

    List<Visit> findByCareRequestIdIn(java.util.Collection<Long> careRequestIds);

    List<Visit> findByCareRequestIdOrderByScheduledAtAsc(Long careRequestId);

    List<Visit> findByProviderIdAndStatusIn(Long providerId, java.util.Collection<String> statuses);

    long countByProviderIdAndStatus(Long providerId, String status);

    long countByProviderIdAndStatusAndCheckOutAtGreaterThanEqual(Long providerId, String status, Instant since);

    List<Visit> findByCareRequestIdInAndStatusAndScheduledAtGreaterThanEqualOrderByScheduledAtAsc(
        java.util.Collection<Long> careRequestIds, String status, Instant from);

    List<Visit> findByCareRequestIdInAndStatusOrderByScheduledAtDesc(java.util.Collection<Long> careRequestIds, String status);
}
