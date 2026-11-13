package com.digihealth.facility;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityInvoiceRepository extends JpaRepository<FacilityInvoice, Long> {

    List<FacilityInvoice> findAllByOrderByCreatedAtDesc();

    List<FacilityInvoice> findByStatusOrderByCreatedAtDesc(String status);

    List<FacilityInvoice> findByFacilityIdOrderByCreatedAtDesc(Long facilityId);

    boolean existsByInvoiceNumber(String invoiceNumber);

    /** Already billed: any invoice for this request that wasn't declined. */
    boolean existsByRequestIdAndStatusNot(Long requestId, String status);
}
