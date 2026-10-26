package com.digihealth.support;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A conversation between a patient and Customer Care. */
@Entity
@Table(name = "support_tickets")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportTicket extends BaseEntity {

    public static final String OPEN = "OPEN";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String ESCALATED = "ESCALATED";
    public static final String RESOLVED = "RESOLVED";
    public static final String CLOSED = "CLOSED";

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String status = OPEN;

    @Column(nullable = false)
    private String priority = "NORMAL";

    @Column(name = "assigned_agent_id")
    private Long assignedAgentId;

    @Column(name = "unread_for_patient", nullable = false)
    private boolean unreadForPatient;

    @Column(name = "unread_for_agent", nullable = false)
    private boolean unreadForAgent = true;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public SupportTicket(Long patientId, String category, String subject) {
        this.patientId = patientId;
        this.category = category;
        this.subject = subject;
    }

    public String reference() {
        return String.format("TKT-%06d", getId());
    }
}
