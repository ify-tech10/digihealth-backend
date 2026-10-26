package com.digihealth.support;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.common.ApiException;
import com.digihealth.common.CurrentUser;
import com.digihealth.notification.NotificationService;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/** Contact Support: the patient's side of a ticket. Customer Care's side is Phase 10. */
@Service
public class PatientSupportService {

    private static final Set<String> CATEGORIES = Set.of("BOOKING", "CAREGIVER", "BILLING", "RESULTS", "MEDICATION", "OTHER");

    private final SupportTicketRepository tickets;
    private final TicketMessageRepository messages;
    private final UserRepository users;
    private final NotificationService notifications;
    private final ActivityService activity;

    public PatientSupportService(SupportTicketRepository tickets, TicketMessageRepository messages, UserRepository users,
                                 NotificationService notifications, ActivityService activity) {
        this.tickets = tickets;
        this.messages = messages;
        this.users = users;
        this.notifications = notifications;
        this.activity = activity;
    }

    public record CreateBody(@NotBlank(message = "Choose a topic") String category,
                             @NotBlank(message = "Add a short subject") @Size(max = 150) String subject,
                             @NotBlank(message = "Write your message") @Size(max = 4000) String message) {
    }

    public record ReplyBody(@NotBlank(message = "Write your message") @Size(max = 4000) String message) {
    }

    public record MessageView(Long id, String from, String authorName, String body, boolean mine, Instant createdAt) {
    }

    public record TicketView(Long id, String reference, String category, String subject, String status,
                             boolean unreadForPatient, Instant createdAt, Instant updatedAt, Instant lastMessageAt,
                             List<MessageView> messages) {
    }

    @Transactional(readOnly = true)
    public List<TicketView> list() {
        return tickets.findByPatientIdOrderByLastMessageAtDesc(CurrentUser.id()).stream()
            .map(t -> view(t, null)).toList();
    }

    /** Opening a ticket marks the agent's replies as read. */
    @Transactional
    public TicketView get(Long id) {
        SupportTicket t = mine(id);
        t.setUnreadForPatient(false);
        return view(t, messages.findByTicketIdOrderByCreatedAtAsc(t.getId()));
    }

    @Transactional
    public TicketView create(CreateBody body) {
        User me = users.findById(CurrentUser.id()).orElseThrow();
        String category = body.category().trim().toUpperCase();
        if (!CATEGORIES.contains(category)) {
            throw ApiException.badRequest("Choose one of the listed topics.");
        }
        SupportTicket t = tickets.save(new SupportTicket(me.getId(), category, body.subject().trim()));
        messages.save(new TicketMessage(t.getId(), me.getId(), TicketMessage.PATIENT, me.getFullName(), body.message().trim()));

        notifications.notifyRoles(Set.of(Role.CUSTOMER_CARE), "New support ticket",
            me.getFullName() + ": " + t.getSubject(), "/ccs/tickets");
        activity.record("TICKET_CREATED", "Opened ticket " + t.reference() + ": " + t.getSubject(), "SUPPORT_TICKET", t.getId());
        return get(t.getId());
    }

    @Transactional
    public TicketView reply(Long id, String text) {
        User me = users.findById(CurrentUser.id()).orElseThrow();
        SupportTicket t = mine(id);
        if (SupportTicket.CLOSED.equals(t.getStatus())) {
            throw ApiException.conflict("This conversation is closed. Please start a new one.");
        }
        messages.save(new TicketMessage(t.getId(), me.getId(), TicketMessage.PATIENT, me.getFullName(), text.trim()));
        if (SupportTicket.RESOLVED.equals(t.getStatus())) {
            t.setStatus(SupportTicket.OPEN);
            t.setResolvedAt(null);
        }
        t.setUnreadForAgent(true);
        t.setLastMessageAt(Instant.now());

        String msg = me.getFullName() + " replied on " + t.reference() + ": " + t.getSubject();
        if (t.getAssignedAgentId() != null) {
            notifications.notify(t.getAssignedAgentId(), "Patient replied", msg, "/ccs/tickets");
        } else {
            notifications.notifyRoles(Set.of(Role.CUSTOMER_CARE), "Patient replied", msg, "/ccs/tickets");
        }
        return get(t.getId());
    }

    private SupportTicket mine(Long id) {
        return tickets.findByIdAndPatientId(id, CurrentUser.id())
            .orElseThrow(() -> ApiException.notFound("Conversation not found."));
    }

    private static TicketView view(SupportTicket t, List<TicketMessage> msgs) {
        return new TicketView(t.getId(), t.reference(), t.getCategory(), t.getSubject(), t.getStatus(),
            t.isUnreadForPatient(), t.getCreatedAt(), t.getUpdatedAt(), t.getLastMessageAt(),
            msgs == null ? null : msgs.stream().map(m -> new MessageView(m.getId(), m.getSenderType(),
                m.getSenderName(), m.getBody(), TicketMessage.PATIENT.equals(m.getSenderType()), m.getCreatedAt())).toList());
    }
}
