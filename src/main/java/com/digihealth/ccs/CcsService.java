package com.digihealth.ccs;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.activity.ActivityService.ActivityView;
import com.digihealth.carerequest.CareRequest;
import com.digihealth.carerequest.CareRequestDtos;
import com.digihealth.carerequest.CareRequestRepository;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.email.EmailService;
import com.digihealth.notification.NotificationService;
import com.digihealth.patient.Patient;
import com.digihealth.patient.PatientRepository;
import com.digihealth.provider.Provider;
import com.digihealth.provider.ProviderRepository;
import com.digihealth.support.SupportTicket;
import com.digihealth.support.SupportTicketRepository;
import com.digihealth.support.TicketMessage;
import com.digihealth.support.TicketMessageRepository;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/*
 * Customer Care: one shared inbox for the team. Replying assigns the ticket
 * to you; status moves OPEN -> PENDING (waiting on patient) -> RESOLVED,
 * or ESCALATED to another team.
 */
@Service
public class CcsService {

    private static final Set<String> CHANNELS = Set.of("PHONE", "WHATSAPP", "EMAIL", "WALK_IN");
    private static final Map<String, Set<Role>> TEAMS = Map.of(
        "CNO", Set.of(Role.CNO, Role.CNO_MEDICAL_DIRECTOR, Role.CHIEF_MEDICAL_OFFICER,
            Role.HEAD_OF_CLINICAL_OPERATIONS, Role.NURSING_SUPERVISOR),
        "FINANCE", Set.of(Role.FINANCE_MANAGER),
        "RELATIONSHIP_MANAGER", Set.of(Role.RELATIONSHIP_MANAGER),
        "ADMIN", Role.ADMINS);
    private static final java.util.regex.Pattern PATIENT_NO = java.util.regex.Pattern.compile("^(?:DH-?)?0*(\\d{1,9})$",
        java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final Set<String> CLOSED_STATES = Set.of(SupportTicket.RESOLVED, SupportTicket.CLOSED);

    private final SupportTicketRepository tickets;
    private final TicketMessageRepository messages;
    private final UserRepository users;
    private final PatientRepository patients;
    private final CareRequestRepository requests;
    private final ProviderRepository providers;
    private final AgentStatusRepository statuses;
    private final NotificationService notifications;
    private final EmailService email;
    private final ActivityService activity;

    public CcsService(SupportTicketRepository tickets, TicketMessageRepository messages, UserRepository users,
                      PatientRepository patients, CareRequestRepository requests, ProviderRepository providers,
                      AgentStatusRepository statuses, NotificationService notifications, EmailService email,
                      ActivityService activity) {
        this.tickets = tickets;
        this.messages = messages;
        this.users = users;
        this.patients = patients;
        this.requests = requests;
        this.providers = providers;
        this.statuses = statuses;
        this.notifications = notifications;
        this.email = email;
        this.activity = activity;
    }

    /* ── DTOs ── */

    public record CreateBody(Long patientId,
                             @NotBlank(message = "Enter the caller's name") @Size(max = 150) String name,
                             @Pattern(regexp = CareRequestDtos.PHONE_RULE, message = "Enter a valid phone number") String phone,
                             @Email(message = "Enter a valid email") @Size(max = 255) String email,
                             @NotBlank(message = "Choose a channel") String channel,
                             @NotBlank(message = "Choose a type") @Size(max = 20) String type,
                             String priority,
                             @NotBlank(message = "Add a subject") @Size(max = 150) String subject,
                             @NotBlank(message = "Describe the issue") @Size(max = 4000) String message) {
    }

    public record ReplyBody(@NotBlank(message = "Write a reply") @Size(max = 4000) String message) {
    }

    public record NoteBody(@Size(max = 2000) String note) {
    }

    public record EscalateBody(@NotBlank(message = "Choose a team") String escalateTo,
                               @NotBlank(message = "Add a note for the team") @Size(max = 2000) String note) {
    }

    public record StatusBody(@NotBlank @Pattern(regexp = "^(ONLINE|AWAY)$", message = "Status is ONLINE or AWAY") String status) {
    }

    public record MessageView(Long id, String from, String name, String text, Instant createdAt) {
    }

    public record PatientCard(Long id, String patientNumber, String name, String fullName, String phone,
                              String phoneNumber, String email, String plan, String nurse, String status,
                              String locationArea, String address, String nextOfKinName, long openTickets) {
    }

    public record TicketView(Long id, String reference, String name, String subject, String preview, String type,
                             String status, String priority, boolean unread, String channel, Long patientId,
                             String assignedAgentName, boolean mine, String escalatedTo, String escalationNote,
                             String resolutionNote, Instant createdAt, Instant updatedAt, PatientCard patient,
                             List<MessageView> messages) {
    }

    public record Stats(long open, long urgent, long escalated, long resolvedToday, long mine, long unread,
                        Double avgTime, Integer csat) {
    }

    public record DayCount(String date, long received, long resolved) {
    }

    public record TypeCount(String type, long count) {
    }

    public record Performance(String month, long ticketsHandled, long resolved, long escalated, Double avgFirstResponse,
                              Double avgResolutionTime, Integer csat, List<DayCount> daily, List<TypeCount> byType,
                              List<Object> feedback) {
    }

    /* ── Inbox ── */

    @Transactional(readOnly = true)
    public List<TicketView> list() {
        List<SupportTicket> list = tickets.findAllByOrderByLastMessageAtDesc();
        Map<Long, List<TicketMessage>> msgs = list.isEmpty() ? Map.of()
            : messages.findByTicketIdInOrderByCreatedAtAsc(list.stream().map(SupportTicket::getId).toList()).stream()
                .collect(Collectors.groupingBy(TicketMessage::getTicketId));
        return views(list, msgs, false);
    }

    /** Opening a ticket marks the patient's messages as read. */
    @Transactional
    public TicketView get(Long id) {
        SupportTicket t = find(id);
        t.setUnreadForAgent(false);
        return views(List.of(t), Map.of(t.getId(), messages.findByTicketIdOrderByCreatedAtAsc(t.getId())), true).get(0);
    }

    @Transactional
    public TicketView create(CreateBody body) {
        User agent = me();
        String channel = body.channel().trim().toUpperCase();
        if (!CHANNELS.contains(channel)) {
            throw ApiException.badRequest("Channel must be phone, WhatsApp, email or walk-in.");
        }
        User patient = null;
        if (body.patientId() != null) {
            patient = users.findById(body.patientId()).filter(u -> u.getRole() == Role.PATIENT)
                .orElseThrow(() -> ApiException.badRequest("That patient wasn't found."));
        }
        SupportTicket t = new SupportTicket(patient == null ? null : patient.getId(), body.type().trim().toUpperCase(),
            body.subject().trim());
        t.setCallerName(body.name().trim());
        t.setCallerPhone(blankToNull(body.phone()));
        t.setCallerEmail(blankToNull(body.email()));
        t.setChannel(channel);
        t.setPriority("URGENT".equalsIgnoreCase(body.priority()) ? "URGENT" : "NORMAL");
        t.setCreatedByAgentId(agent.getId());
        t.setAssignedAgentId(agent.getId());
        t.setUnreadForAgent(false);
        t = tickets.save(t);
        /* The caller's issue, as they described it. */
        messages.save(new TicketMessage(t.getId(), null, TicketMessage.PATIENT, t.getCallerName(), body.message().trim()));

        activity.record("TICKET_LOGGED", "Logged " + channel.toLowerCase().replace('_', '-') + " ticket "
            + t.reference() + " for " + t.getCallerName(), "SUPPORT_TICKET", t.getId());
        return get(t.getId());
    }

    @Transactional
    public TicketView reply(Long id, String text) {
        User agent = me();
        SupportTicket t = find(id);
        if (SupportTicket.CLOSED.equals(t.getStatus())) {
            throw ApiException.conflict("This ticket is closed.");
        }
        Instant now = Instant.now();
        messages.save(new TicketMessage(t.getId(), agent.getId(), TicketMessage.AGENT, agent.getFullName(), text.trim()));
        if (t.getAssignedAgentId() == null) {
            t.setAssignedAgentId(agent.getId());
        }
        if (t.getFirstResponseAt() == null) {
            t.setFirstResponseAt(now);
            t.setFirstResponseById(agent.getId());
        }
        if (!SupportTicket.ESCALATED.equals(t.getStatus())) {
            t.setStatus(SupportTicket.PENDING);
        }
        t.setResolvedAt(null);
        t.setLastMessageAt(now);
        t.setUnreadForAgent(false);
        t.setUnreadForPatient(true);

        if (t.getPatientId() != null) {
            notifications.notify(t.getPatientId(), "Customer Care replied", t.getSubject(), "/patient/support");
            users.findById(t.getPatientId()).ifPresent(u -> email.sendNotice(u.getEmail(), u.getFullName(),
                "You have a reply from DiGi Health", "You have a reply from our team",
                List.of("Our Customer Care team replied to your message \"" + t.getSubject() + "\".",
                    "Sign in to DiGi Health and open Contact Support to read it.")));
        }
        activity.record("TICKET_REPLIED", "Replied on " + t.reference(), "SUPPORT_TICKET", t.getId());
        return get(t.getId());
    }

    @Transactional
    public TicketView resolve(Long id, String note) {
        User agent = me();
        SupportTicket t = find(id);
        if (t.isResolved()) {
            throw ApiException.conflict("This ticket is already resolved.");
        }
        t.setStatus(SupportTicket.RESOLVED);
        t.setResolvedAt(Instant.now());
        t.setResolvedById(agent.getId());
        t.setResolutionNote(blankToNull(note));
        if (t.getAssignedAgentId() == null) {
            t.setAssignedAgentId(agent.getId());
        }
        if (t.getPatientId() != null) {
            t.setUnreadForPatient(true);
            notifications.notify(t.getPatientId(), "Request resolved",
                "\"" + t.getSubject() + "\" was marked as resolved. Reply if you still need help.", "/patient/support");
        }
        activity.record("TICKET_RESOLVED", "Resolved " + t.reference(), "SUPPORT_TICKET", t.getId());
        return get(t.getId());
    }

    @Transactional
    public TicketView escalate(Long id, EscalateBody body) {
        User agent = me();
        SupportTicket t = find(id);
        if (t.isResolved()) {
            throw ApiException.conflict("Reopen the ticket before escalating it.");
        }
        String team = body.escalateTo().trim().toUpperCase();
        Set<Role> roles = TEAMS.get(team);
        if (roles == null) {
            throw ApiException.badRequest("Choose clinical, finance, relationship manager or admin.");
        }
        t.setStatus(SupportTicket.ESCALATED);
        t.setEscalatedTo(team);
        t.setEscalationNote(body.note().trim());
        t.setEscalatedAt(Instant.now());
        t.setEscalatedById(agent.getId());

        notifications.notifyRoles(roles, "Escalated by Customer Care",
            t.reference() + " (" + displayName(t) + "): " + t.getSubject() + " - " + t.getEscalationNote(), null);
        activity.record("TICKET_ESCALATED", "Escalated " + t.reference() + " to " + team.toLowerCase().replace('_', ' '),
            "SUPPORT_TICKET", t.getId());
        return get(t.getId());
    }

    @Transactional
    public TicketView reopen(Long id) {
        SupportTicket t = find(id);
        if (!t.isResolved() && !SupportTicket.ESCALATED.equals(t.getStatus())) {
            throw ApiException.conflict("This ticket is already open.");
        }
        t.setStatus(SupportTicket.OPEN);
        t.setResolvedAt(null);
        t.setResolvedById(null);
        activity.record("TICKET_REOPENED", "Reopened " + t.reference(), "SUPPORT_TICKET", t.getId());
        return get(t.getId());
    }

    /* ── Dashboard, status, activity, performance ── */

    @Transactional(readOnly = true)
    public Stats stats() {
        Long me = CurrentUser.id();
        List<SupportTicket> all = tickets.findAllByOrderByLastMessageAtDesc();
        Instant todayStart = AppTime.startOf(AppTime.today());
        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        long open = all.stream().filter(t -> !t.isResolved()).count();
        long urgent = all.stream().filter(t -> !t.isResolved() && "URGENT".equals(t.getPriority())).count();
        long escalated = all.stream().filter(t -> SupportTicket.ESCALATED.equals(t.getStatus())).count();
        long resolvedToday = all.stream().filter(t -> t.getResolvedAt() != null && !t.getResolvedAt().isBefore(todayStart)).count();
        long mine = all.stream().filter(t -> !t.isResolved() && me.equals(t.getAssignedAgentId())).count();
        long unread = all.stream().filter(SupportTicket::isUnreadForAgent).count();
        OptionalDouble firstResponse = all.stream()
            .filter(t -> t.getFirstResponseAt() != null && t.getCreatedAt().isAfter(weekAgo))
            .mapToLong(t -> Duration.between(t.getCreatedAt(), t.getFirstResponseAt()).toMinutes()).average();
        return new Stats(open, urgent, escalated, resolvedToday, mine, unread,
            firstResponse.isPresent() ? round1(firstResponse.getAsDouble()) : null, null);
    }

    @Transactional(readOnly = true)
    public Map<String, String> status() {
        return Map.of("status", statuses.findByUserId(CurrentUser.id()).map(AgentStatus::getStatus).orElse("ONLINE"));
    }

    @Transactional
    public Map<String, String> setStatus(String status) {
        Long me = CurrentUser.id();
        AgentStatus s = statuses.findByUserId(me).orElseGet(() -> statuses.save(new AgentStatus(me)));
        s.setStatus(status);
        return Map.of("status", s.getStatus());
    }

    @Transactional(readOnly = true)
    public List<ActivityView> activity(int limit) {
        return activity.recentBy(CurrentUser.id(), limit);
    }

    @Transactional(readOnly = true)
    public Performance performance(String monthText) {
        Long me = CurrentUser.id();
        YearMonth month;
        try {
            month = monthText == null || monthText.isBlank() ? YearMonth.now(AppTime.ZONE) : YearMonth.parse(monthText.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Month must look like 2026-10.");
        }
        Instant from = AppTime.startOf(month.atDay(1));
        Instant to = AppTime.endOf(month.atEndOfMonth());

        List<TicketMessage> myReplies = messages.findBySenderIdAndSenderTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            me, TicketMessage.AGENT, from, to);
        List<SupportTicket> all = tickets.findAllByOrderByLastMessageAtDesc();
        Set<Long> repliedTo = myReplies.stream().map(TicketMessage::getTicketId).collect(Collectors.toSet());
        List<SupportTicket> handled = all.stream().filter(t -> repliedTo.contains(t.getId())
            || (me.equals(t.getResolvedById()) && inRange(t.getResolvedAt(), from, to))
            || (me.equals(t.getCreatedByAgentId()) && inRange(t.getCreatedAt(), from, to))).toList();
        List<SupportTicket> resolved = all.stream().filter(t -> me.equals(t.getResolvedById()) && inRange(t.getResolvedAt(), from, to)).toList();
        long escalated = all.stream().filter(t -> me.equals(t.getEscalatedById()) && inRange(t.getEscalatedAt(), from, to)).count();

        OptionalDouble firstResponse = all.stream()
            .filter(t -> me.equals(t.getFirstResponseById()) && inRange(t.getFirstResponseAt(), from, to))
            .mapToLong(t -> Duration.between(t.getCreatedAt(), t.getFirstResponseAt()).toMinutes()).average();
        OptionalDouble resolution = resolved.stream()
            .mapToLong(t -> Duration.between(t.getCreatedAt(), t.getResolvedAt()).toMinutes()).average();

        Map<LocalDate, long[]> days = new LinkedHashMap<>();
        month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1)).forEach(d -> days.put(d, new long[2]));
        tickets.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to)
            .forEach(t -> days.get(LocalDate.ofInstant(t.getCreatedAt(), AppTime.ZONE))[0]++);
        resolved.forEach(t -> days.get(LocalDate.ofInstant(t.getResolvedAt(), AppTime.ZONE))[1]++);

        Map<String, Long> types = handled.stream().collect(Collectors.groupingBy(SupportTicket::getCategory, Collectors.counting()));
        return new Performance(month.toString(), handled.size(), resolved.size(), escalated,
            firstResponse.isPresent() ? round1(firstResponse.getAsDouble()) : null,
            resolution.isPresent() ? round1(resolution.getAsDouble()) : null, null,
            days.entrySet().stream().map(e -> new DayCount(e.getKey().toString(), e.getValue()[0], e.getValue()[1])).toList(),
            types.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new TypeCount(e.getKey(), e.getValue())).toList(),
            List.of());
    }

    /* ── Patients ── */

    @Transactional(readOnly = true)
    public List<PatientCard> search(String q) {
        String text = q == null ? "" : q.trim();
        if (text.length() < 2) {
            return List.of();
        }
        Map<Long, User> found = new LinkedHashMap<>();
        Matcher m = PATIENT_NO.matcher(text.replace(" ", ""));
        if (m.matches()) {
            users.findById(Long.valueOf(m.group(1))).filter(u -> u.getRole() == Role.PATIENT)
                .ifPresent(u -> found.put(u.getId(), u));
        }
        String like = "%" + text.toLowerCase().replace(" ", "%") + "%";
        users.search(Role.PATIENT, like, PageRequest.of(0, 25)).forEach(u -> found.putIfAbsent(u.getId(), u));
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.length() >= 6) {
            users.search(Role.PATIENT, "%" + digits.substring(Math.max(0, digits.length() - 9)) + "%", PageRequest.of(0, 25))
                .forEach(u -> found.putIfAbsent(u.getId(), u));
        }
        return cards(List.copyOf(found.values()));
    }

    @Transactional(readOnly = true)
    public PatientCard patient(Long id) {
        User u = users.findById(id).filter(x -> x.getRole() == Role.PATIENT)
            .orElseThrow(() -> ApiException.notFound("Patient not found."));
        return cards(List.of(u)).get(0);
    }

    /* ── helpers ── */

    private List<PatientCard> cards(List<User> list) {
        List<Long> ids = list.stream().map(User::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Patient> profiles = patients.findByUserIdIn(ids).stream()
            .collect(Collectors.toMap(Patient::getUserId, Function.identity()));
        Map<Long, Long> openTickets = tickets.findByPatientIdInAndStatusNotIn(ids, CLOSED_STATES).stream()
            .collect(Collectors.groupingBy(SupportTicket::getPatientId, Collectors.counting()));
        Map<Long, String> nurses = new HashMap<>();
        for (Long id : ids) {
            requests.findByPatientIdOrderByCreatedAtDesc(id).stream()
                .filter(r -> r.getAssignedProviderId() != null
                    && (CareRequest.ASSIGNED.equals(r.getStatus()) || CareRequest.PENDING_CLOSURE.equals(r.getStatus())))
                .findFirst()
                .flatMap(r -> providers.findById(r.getAssignedProviderId()))
                .map(Provider::getFullName)
                .ifPresent(n -> nurses.put(id, n));
        }
        return list.stream().map(u -> {
            Patient p = profiles.get(u.getId());
            return new PatientCard(u.getId(), String.format("DH-%06d", u.getId()), u.getFullName(), u.getFullName(),
                u.getPhoneNumber(), u.getPhoneNumber(), u.getEmail(), p == null ? Patient.STANDARD : p.getPatientType(),
                nurses.get(u.getId()), u.isActive() ? "ACTIVE" : "INACTIVE", u.getLocationArea(),
                p == null ? null : p.getAddress(), p == null ? null : p.getNextOfKinName(),
                openTickets.getOrDefault(u.getId(), 0L));
        }).toList();
    }

    private List<TicketView> views(List<SupportTicket> list, Map<Long, List<TicketMessage>> msgs, boolean withMessages) {
        Long me = CurrentUser.id();
        Set<Long> patientIds = list.stream().map(SupportTicket::getPatientId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PatientCard> cards = cards(users.findAllById(patientIds)).stream()
            .collect(Collectors.toMap(PatientCard::id, Function.identity()));
        Map<Long, String> agents = users.findAllById(list.stream().map(SupportTicket::getAssignedAgentId)
                .filter(Objects::nonNull).distinct().toList())
            .stream().collect(Collectors.toMap(User::getId, User::getFullName));

        return list.stream().map(t -> {
            List<TicketMessage> ms = msgs.getOrDefault(t.getId(), List.of());
            String preview = ms.isEmpty() ? null : ms.get(ms.size() - 1).getBody();
            if (preview != null && preview.length() > 140) {
                preview = preview.substring(0, 140) + "…";
            }
            PatientCard card = t.getPatientId() == null ? null : cards.get(t.getPatientId());
            if (card == null) {
                card = new PatientCard(null, null, t.getCallerName(), t.getCallerName(), t.getCallerPhone(),
                    t.getCallerPhone(), t.getCallerEmail(), null, null, "NOT_REGISTERED", null, null, null, 0);
            }
            return new TicketView(t.getId(), t.reference(), displayName(t, card), t.getSubject(), preview, t.getCategory(),
                t.getStatus(), t.getPriority(), t.isUnreadForAgent(), t.getChannel(), t.getPatientId(),
                t.getAssignedAgentId() == null ? null : agents.get(t.getAssignedAgentId()),
                me.equals(t.getAssignedAgentId()), t.getEscalatedTo(), t.getEscalationNote(), t.getResolutionNote(),
                t.getCreatedAt(), t.getLastMessageAt(), card,
                withMessages ? ms.stream().map(m -> new MessageView(m.getId(),
                    TicketMessage.AGENT.equals(m.getSenderType()) ? "agent" : "patient", m.getSenderName(), m.getBody(),
                    m.getCreatedAt())).toList() : null);
        }).sorted(Comparator.comparing(TicketView::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    private String displayName(SupportTicket t) {
        if (t.getPatientId() != null) {
            return users.findById(t.getPatientId()).map(User::getFullName).orElse(t.getCallerName());
        }
        return t.getCallerName();
    }

    private static String displayName(SupportTicket t, PatientCard card) {
        return card != null && card.name() != null ? card.name() : t.getCallerName();
    }

    private SupportTicket find(Long id) {
        return tickets.findById(id).orElseThrow(() -> ApiException.notFound("Ticket not found."));
    }

    private User me() {
        return users.findById(CurrentUser.id()).orElseThrow();
    }

    private static boolean inRange(Instant i, Instant from, Instant to) {
        return i != null && !i.isBefore(from) && i.isBefore(to);
    }

    private static Double round1(double d) {
        return Math.round(d * 10) / 10.0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
