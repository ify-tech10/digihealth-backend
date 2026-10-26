package com.digihealth.ccs;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentStatusRepository extends JpaRepository<AgentStatus, Long> {

    Optional<AgentStatus> findByUserId(Long userId);
}
