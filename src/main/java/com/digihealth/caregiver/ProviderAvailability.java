package com.digihealth.caregiver;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "provider_availability")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProviderAvailability extends BaseEntity {

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(nullable = false)
    private LocalDate day;

    @Column(nullable = false)
    private boolean available;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    public ProviderAvailability(Long providerId, LocalDate day) {
        this.providerId = providerId;
        this.day = day;
    }
}
