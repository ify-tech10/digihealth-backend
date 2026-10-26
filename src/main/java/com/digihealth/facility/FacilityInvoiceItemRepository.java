package com.digihealth.facility;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityInvoiceItemRepository extends JpaRepository<FacilityInvoiceItem, Long> {

    List<FacilityInvoiceItem> findByInvoiceIdInOrderByIdAsc(Collection<Long> invoiceIds);
}
