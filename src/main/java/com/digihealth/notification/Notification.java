package com.digihealth.notification;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column
    private String message;

    /** A frontend path such as /patient/bookings, or null. */
    @Column
    private String link;

    @Column(name = "read_at")
    private Instant readAt;

    public Notification(Long userId, String title, String message, String link) {
        this.userId = userId;
        this.title = title;
        this.message = message;
        this.link = link;
    }
}
