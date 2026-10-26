package com.digihealth.caregiver;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderPayoutRepository extends JpaRepository<ProviderPayout, Long> {

    List<ProviderPayout> findByProviderIdOrderByPeriodEndDesc(Long providerId);
}
