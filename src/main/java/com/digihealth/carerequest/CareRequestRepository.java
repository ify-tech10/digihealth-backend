package com.digihealth.carerequest;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CareRequestRepository extends JpaRepository<CareRequest, Long> {

    List<CareRequest> findByPatientIdOrderByCreatedAtDesc(Long patientId);

    java.util.Optional<CareRequest> findByIdAndPatientId(Long id, Long patientId);

    List<CareRequest> findByStatusOrderByCreatedAtDesc(String status);

    List<CareRequest> findAllByOrderByCreatedAtDesc();

    long countByStatus(String status);

    List<CareRequest> findByAssignedProviderIdOrderByCreatedAtDesc(Long providerId);

    long countByAssignedProviderIdAndStatusAndClosedAtGreaterThanEqual(Long providerId, String status, java.time.Instant since);

    long countByAssignedProviderIdAndStatusIn(Long providerId, Collection<String> statuses);

    List<CareRequest> findByStatusIn(Collection<String> statuses);

    long countByStatusAndCreatedAtLessThan(String status, java.time.Instant before);

    /** [providerId, number of open requests] for each provider given. */
    @Query("""
        select r.assignedProviderId, count(r) from CareRequest r
        where r.assignedProviderId in :ids and r.status in :statuses
        group by r.assignedProviderId""")
    List<Object[]> countOpenByProvider(@Param("ids") Collection<Long> providerIds,
                                       @Param("statuses") Collection<String> statuses);
}
