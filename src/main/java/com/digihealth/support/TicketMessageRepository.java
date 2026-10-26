package com.digihealth.support;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, Long> {

    List<TicketMessage> findByTicketIdOrderByCreatedAtAsc(Long ticketId);

    List<TicketMessage> findByTicketIdInOrderByCreatedAtAsc(java.util.Collection<Long> ticketIds);

    List<TicketMessage> findBySenderIdAndSenderTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
        Long senderId, String senderType, java.time.Instant from, java.time.Instant to);
}
