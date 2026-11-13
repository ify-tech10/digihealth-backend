package com.digihealth.finance;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HmoClaimRepository extends JpaRepository<HmoClaim, Long> {

    List<HmoClaim> findAllByOrderBySubmittedAtDescIdDesc();

    boolean existsByClaimNumber(String claimNumber);
}
