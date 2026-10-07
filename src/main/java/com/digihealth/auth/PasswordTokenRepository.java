package com.digihealth.auth;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordTokenRepository extends JpaRepository<PasswordToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordToken t join fetch t.user where t.tokenHash = :hash")
    Optional<PasswordToken> findForUpdate(@Param("hash") String hash);

    /** Was a link sent to this user recently? (stops inbox flooding) */
    @Query("select case when count(t) > 0 then true else false end from PasswordToken t where t.user.id = :userId and t.createdAt > :since")
    boolean sentSince(@Param("userId") Long userId, @Param("since") Instant since);

    /** Older links stop working once a new one is sent or the password is set. */
    @Modifying
    @Query("update PasswordToken t set t.usedAt = :now where t.user.id = :userId and t.usedAt is null")
    int invalidateAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from PasswordToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
