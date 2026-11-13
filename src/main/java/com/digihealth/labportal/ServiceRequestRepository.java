package com.digihealth.labportal;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, Long> {

    /** What one lab scientist / pharmacist sees: unclaimed requests of their kind, plus the ones they handle. */
    @Query("select r from ServiceRequest r where r.kind = :kind and (r.handledById = :me or r.handledById is null) "
        + "order by r.createdAt desc")
    List<ServiceRequest> visibleTo(@Param("kind") String kind, @Param("me") Long me);

    Optional<ServiceRequest> findByIdAndKind(Long id, String kind);

    boolean existsByRequestNumber(String requestNumber);
}
