package com.digihealth.caregiver;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderAvailabilityRepository extends JpaRepository<ProviderAvailability, Long> {

    List<ProviderAvailability> findByProviderIdAndDayBetweenOrderByDayAsc(Long providerId, LocalDate from, LocalDate to);
}
