package com.campussphere.backend.controller;

import java.util.List;
import java.util.Map;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.FacultyNotifications;
import com.campussphere.backend.service.FacultyNotifications.Notice;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** The signed-in faculty member's own timetable notifications (covered by the /api/faculty/** FACULTY rule). */
@RestController
@RequestMapping("/api/faculty/notifications")
public class FacultyNotificationController {
    private final FacultyNotifications notifications;

    public FacultyNotificationController(FacultyNotifications notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    List<Notice> list(@AuthenticationPrincipal Account actor, @RequestParam(defaultValue = "false") boolean unread,
                      @RequestParam(defaultValue = "0") int page) {
        return notifications.list(actor, unread, page);
    }

    @GetMapping("/unread-count")
    Map<String, Long> unread(@AuthenticationPrincipal Account actor) {
        return Map.of("count", notifications.unread(actor));
    }

    @PostMapping("/{id:\\d+}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        notifications.markRead(actor, id);
    }

    @PostMapping("/read-all")
    Map<String, Integer> readAll(@AuthenticationPrincipal Account actor) {
        return Map.of("updated", notifications.markAllRead(actor));
    }
}
