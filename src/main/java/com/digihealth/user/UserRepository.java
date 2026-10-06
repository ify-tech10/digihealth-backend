package com.digihealth.user;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Pass the email through User.normalizeEmail first. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRoleIn(Collection<Role> roles);
}
