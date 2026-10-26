package com.digihealth.hmo;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HmoSubscriptionRepository extends JpaRepository<HmoSubscription, Long> {

    List<HmoSubscription> findAllByOrderByExpiryDateAsc();

    List<HmoSubscription> findByPlanIdIn(Collection<Long> planIds);

    boolean existsByOrganisationIdAndStatusInAndExpiryDateGreaterThanEqual(Long organisationId,
                                                                          Collection<String> statuses, LocalDate date);

    boolean existsByPatientIdAndStatusInAndExpiryDateGreaterThanEqual(Long patientId,
                                                                     Collection<String> statuses, LocalDate date);

    long countByPlanIdAndStatus(Long planId, String status);

    List<HmoSubscription> findByPatientIdOrderByExpiryDateDesc(Long patientId);

    List<HmoSubscription> findByOrganisationIdOrderByExpiryDateDesc(Long organisationId);
}
