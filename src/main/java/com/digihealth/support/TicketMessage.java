package com.digihealth.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ticket_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketMessage extends BaseEntity {

    public static final String PATIENT = "PATIENT";
    public static final String AGENT = "AGENT";

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "sender_id")
    private Long senderId;

    @Column(name = "sender_type", nullable = false)
    private String senderType;

    @Column(name = "sender_name")
    private String senderName;

    @Column(nullable = false)
    private String body;

    public TicketMessage(Long ticketId, Long senderId, String senderType, String senderName, String body) {
        this.ticketId = ticketId;
        this.senderId = senderId;
        this.senderType = senderType;
        this.senderName = senderName;
        this.body = body;
    }
}
