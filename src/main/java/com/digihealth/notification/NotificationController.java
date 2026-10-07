package com.digihealth.notification;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digihealth.common.CurrentUser;
import com.digihealth.notification.NotificationService.NotificationView;

/** The bell in every portal. A user only ever sees and changes their own. */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public List<NotificationView> mine() {
        return service.latestFor(CurrentUser.id());
    }

    @PutMapping("/me/read-all")
    public ResponseEntity<Void> readAll() {
        service.markAllRead(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable("id") Long id) {
        service.markRead(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }
}
