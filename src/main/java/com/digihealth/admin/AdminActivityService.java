package com.digihealth.admin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.Activity;
import com.digihealth.activity.ActivityRepository;
import com.digihealth.activity.ActivityService.ActivityView;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;

/** Admin > Activity Log: filter by role and a date range (Lagos days, inclusive). */
@Service
public class AdminActivityService {

    private static final int MAX_ROWS = 2000;

    private final ActivityRepository activities;

    public AdminActivityService(ActivityRepository activities) {
        this.activities = activities;
    }

    @Transactional(readOnly = true)
    public List<ActivityView> search(String role, String fromText, String toText) {
        LocalDate from = AppTime.parseDate(fromText, "from");
        LocalDate to = AppTime.parseDate(toText, "to");
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("'to' must be on or after 'from'.");
        }
        String roleFilter = role == null || role.isBlank() ? null : role.trim().toUpperCase();

        Specification<Activity> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (roleFilter != null) {
                where.add(cb.equal(root.get("actorRole"), roleFilter));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), AppTime.startOf(from)));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("createdAt"), AppTime.endOf(to)));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        return activities.findAll(spec, PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "createdAt")))
            .map(ActivityView::of)
            .getContent();
    }
}
