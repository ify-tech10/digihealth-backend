package com.digihealth.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Pass the email through User.normalizeEmail first. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRoleIn(Collection<Role> roles);

    List<User> findByRoleInAndActiveTrue(Collection<Role> roles);

    List<User> findAllByOrderByCreatedAtDesc();

    List<User> findByRoleOrderByCreatedAtDesc(Role role);

    long countByRoleInAndActiveTrue(Collection<Role> roles);

    /** Patient lookup: name, email or phone contains the text (pass "%text%", lower-case). */
    @org.springframework.data.jpa.repository.Query("""
        select u from User u where u.role = :role and (
            lower(u.fullName) like :q or lower(u.email) like :q or replace(u.phoneNumber, ' ', '') like :q)
        order by u.fullName""")
    List<User> search(@org.springframework.data.repository.query.Param("role") Role role,
                      @org.springframework.data.repository.query.Param("q") String q,
                      org.springframework.data.domain.Pageable page);
}
