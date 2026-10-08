package com.digihealth.provider;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderRepository extends JpaRepository<Provider, Long> {

    boolean existsByEmailAndStatusIn(String email, Collection<String> statuses);

    Optional<Provider> findByUserId(Long userId);

    List<Provider> findByStatusOrderByCreatedAtDesc(String status);

    List<Provider> findAllByOrderByCreatedAtDesc();

    long countByStatus(String status);
}
