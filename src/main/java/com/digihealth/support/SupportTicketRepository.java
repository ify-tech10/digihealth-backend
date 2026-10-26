package com.digihealth.support;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    List<SupportTicket> findByPatientIdOrderByLastMessageAtDesc(Long patientId);

    Optional<SupportTicket> findByIdAndPatientId(Long id, Long patientId);

    List<SupportTicket> findAllByOrderByLastMessageAtDesc();

    List<SupportTicket> findByPatientIdInAndStatusNotIn(java.util.Collection<Long> patientIds, java.util.Collection<String> statuses);

    List<SupportTicket> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(java.time.Instant from, java.time.Instant to);
}
