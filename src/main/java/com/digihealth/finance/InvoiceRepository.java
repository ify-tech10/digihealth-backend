package com.digihealth.finance;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findAllByOrderByCreatedAtDesc();

    List<Invoice> findByPatientUserIdAndStatusNotOrderByCreatedAtDesc(Long patientUserId, String status);

    boolean existsByInvoiceNumber(String invoiceNumber);
}
