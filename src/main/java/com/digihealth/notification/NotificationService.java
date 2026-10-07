package com.digihealth.notification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.common.ApiException;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/*
 * Every module calls notify(...) when someone needs to know about something:
 *   notifications.notify(patientId, "Visit confirmed", "Your nurse is booked for Tue 9am.", "/patient/bookings");
 *   notifications.notifyRoles(Role.ADMINS, "New care request", "...", "/admin/care-requests");
 */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final UserRepository users;

    public NotificationService(NotificationRepository notifications, UserRepository users) {
        this.notifications = notifications;
        this.users = users;
    }

    @Transactional
    public void notify(Long userId, String title, String message, String link) {
        notifications.save(new Notification(userId, title, message, link));
    }

    /** Everyone active with one of these roles, e.g. all admins. */
    @Transactional
    public void notifyRoles(Collection<Role> roles, String title, String message, String link) {
        List<Notification> batch = users.findByRoleInAndActiveTrue(roles).stream()
            .map(User::getId)
            .map(id -> new Notification(id, title, message, link))
            .toList();
        notifications.saveAll(batch);
    }

    @Transactional(readOnly = true)
    public List<NotificationView> latestFor(Long userId) {
        return notifications.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(NotificationView::of)
            .toList();
    }

    @Transactional
    public void markRead(Long userId, Long notificationId) {
        Notification n = notifications.findByIdAndUserId(notificationId, userId)
            .orElseThrow(() -> ApiException.notFound("Notification not found."));
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
        }
    }

    @Transactional
    public void markAllRead(Long userId) {
        notifications.markAllRead(userId, Instant.now());
    }

    /** What the bell receives: { id, title, message, link, read, createdAt } */
    public record NotificationView(Long id, String title, String message, String link, boolean read, Instant createdAt) {
        static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getTitle(), n.getMessage(), n.getLink(),
                n.getReadAt() != null, n.getCreatedAt());
        }
    }
}
