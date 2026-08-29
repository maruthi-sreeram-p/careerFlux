package com.careerflux.notification.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.careerflux.notification.domain.Notification;
import com.careerflux.notification.service.NotificationService;
import com.careerflux.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUser currentUser;

    public NotificationController(NotificationService notificationService, CurrentUser currentUser) {
        this.notificationService = notificationService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Page<NotificationView> list(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "25") int size) {
        return notificationService.list(currentUser.requireId(), PageRequest.of(page, Math.min(size, 100)))
                .map(this::toView);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notificationService.unreadCount(currentUser.requireId()));
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID notificationId) {
        notificationService.markRead(currentUser.requireId(), notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark every notification read")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", notificationService.markAllRead(currentUser.requireId()));
    }

    private NotificationView toView(Notification notification) {
        return new NotificationView(
                notification.getId(),
                notification.getCategory().name(),
                notification.getPriority().name(),
                notification.getTitle(),
                notification.getBody(),
                notification.getJob() == null ? null : notification.getJob().getId(),
                notification.getJob() == null ? null : notification.getJob().getTitle(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }

    public record NotificationView(
            UUID id,
            String category,
            String priority,
            String title,
            String body,
            UUID jobId,
            String jobTitle,
            Instant readAt,
            Instant createdAt) {
    }
}
