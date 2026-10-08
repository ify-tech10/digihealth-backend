package com.digihealth.hmo;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HmoOrganisationRepository extends JpaRepository<HmoOrganisation, Long> {

    @Query("""
        select case when count(o) > 0 then true else false end from HmoOrganisation o
        where o.status in :statuses and (o.email = :email or lower(o.companyName) = lower(:name))""")
    boolean existsActive(@Param("email") String email, @Param("name") String companyName,
                         @Param("statuses") Collection<String> statuses);

    List<HmoOrganisation> findByStatusOrderByCreatedAtDesc(String status);

    List<HmoOrganisation> findAllByOrderByCreatedAtDesc();

    long countByStatus(String status);
}
