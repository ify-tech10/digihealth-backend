package com.digihealth.activity;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/* The admin, CNO, CCS, Finance and Lab "activity" endpoints read from here.
   Filtered searches (role, date range) use JpaSpecificationExecutor. */
public interface ActivityRepository extends JpaRepository<Activity, Long>, JpaSpecificationExecutor<Activity> {

    List<Activity> findByActorIdOrderByCreatedAtDesc(Long actorId, Pageable page);

    List<Activity> findByOrderByCreatedAtDesc(Pageable page);
}
