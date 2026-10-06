package com.digihealth.user;

import java.time.Instant;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Column(nullable = false, unique = true)
    @Setter(AccessLevel.NONE)
    private String email;

    /** BCrypt hash; null until the user sets a password from their email link. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "location_area")
    private String locationArea;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public User(String email, String fullName, Role role) {
        setEmail(email);
        this.fullName = fullName;
        this.role = role;
    }

    /** Emails are stored trimmed and lower-cased so lookups never miss on case. */
    public void setEmail(String email) {
        this.email = normalizeEmail(email);
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
