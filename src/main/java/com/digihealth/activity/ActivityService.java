package com.digihealth.activity;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.common.CurrentUser;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/*
 * Records "who did what". Call it from services after the action succeeds:
 *   activity.record("CARE_REQUEST_ASSIGNED", "Assigned Nurse Ada to request #12", "CARE_REQUEST", 12L);
 * Uses the signed-in user; pass the User explicitly where nobody is signed in yet (login).
 */
@Service
public class ActivityService {

    private final ActivityRepository activities;
    private final UserRepository users;

    public ActivityService(ActivityRepository activities, UserRepository users) {
        this.activities = activities;
        this.users = users;
    }

    @Transactional
    public void record(String action, String description, String entityType, Long entityId) {
        Long actorId = CurrentUser.idOrNull();
        User actor = actorId == null ? null : users.findById(actorId).orElse(null);
        record(actor, action, description, entityType, entityId);
    }

    @Transactional
    public void record(User actor, String action, String description, String entityType, Long entityId) {
        activities.save(new Activity(
            actor == null ? null : actor.getId(),
            actor == null ? "System" : actor.getFullName(),
            actor == null ? null : actor.getRole().name(),
            action, description, entityType, entityId));
    }

    @Transactional(readOnly = true)
    public List<ActivityView> recent(int limit) {
        return activities.findByOrderByCreatedAtDesc(PageRequest.of(0, clamp(limit))).stream()
            .map(ActivityView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<ActivityView> recentBy(Long actorId, int limit) {
        return activities.findByActorIdOrderByCreatedAtDesc(actorId, PageRequest.of(0, clamp(limit))).stream()
            .map(ActivityView::of).toList();
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }

    /** Field names match the frontend activity tables. */
    public record ActivityView(Long id, String actorName, String actorRole, String action, String description,
                               String entityType, Long entityId, Instant createdAt) {
        public static ActivityView of(Activity a) {
            return new ActivityView(a.getId(), a.getActorName(), a.getActorRole(), a.getAction(),
                a.getDescription(), a.getEntityType(), a.getEntityId(), a.getCreatedAt());
        }
    }
}
