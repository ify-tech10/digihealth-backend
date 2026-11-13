package com.digihealth.payment;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findAllByOrderByCreatedAtDesc();

    List<Payment> findByPayerUserIdOrderByCreatedAtDesc(Long payerUserId);

    java.util.Optional<Payment> findFirstByReferenceAndMethod(String reference, String method);

    List<Payment> findByPayerUserIdAndMethodAndStatus(Long payerUserId, String method, String status);

    List<Payment> findByStatusOrderByPaidAtDesc(String status);

    /** Flips a PENDING online payment to PAID exactly once, even if the webhook and the patient's page race. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
        "update Payment p set p.status = 'PAID', p.paidAt = :paidAt where p.id = :id and p.status = 'PENDING'")
    int markPaidIfPending(@org.springframework.data.repository.query.Param("id") Long id,
                          @org.springframework.data.repository.query.Param("paidAt") java.time.Instant paidAt);
}
