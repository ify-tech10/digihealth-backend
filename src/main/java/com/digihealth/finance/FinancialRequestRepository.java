package com.digihealth.finance;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialRequestRepository extends JpaRepository<FinancialRequest, Long> {

    List<FinancialRequest> findAllByOrderByCreatedAtDesc();

    List<FinancialRequest> findByStatusOrderByCreatedAtDesc(String status);

    List<FinancialRequest> findByRequestedByIdOrderByCreatedAtDesc(Long userId);
}
