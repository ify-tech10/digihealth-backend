package com.digihealth.facility;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityInvoiceRepository extends JpaRepository<FacilityInvoice, Long> {

    List<FacilityInvoice> findAllByOrderByCreatedAtDesc();

    List<FacilityInvoice> findByStatusOrderByCreatedAtDesc(String status);

    List<FacilityInvoice> findByFacilityIdOrderByCreatedAtDesc(Long facilityId);
}
