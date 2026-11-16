package com.leyon.lumin.engagement.api;

import com.leyon.lumin.foundation.web.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.engagement.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知接入层（05 §3.4：listNotifications / markNotificationRead）。
 * 仅本人可见；重复已读幂等（T33）。
 */
@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ApiEnvelope list(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject) {
        return ApiEnvelope.ok(notificationService.listUnread(subject).stream()
                .map(row -> new NotificationView(row.id(), row.category(), row.title(),
                        row.body(), row.resourceType(), row.resourceId()))
                .toList());
    }

    /** 标记已读（幂等：重复已读 404 由守卫语义替代，不重复产生副作用）。 */
    @PostMapping("/{id}/read-receipts")
    public ApiEnvelope markRead(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id) {
        notificationService.markRead(subject, id);
        return ApiEnvelope.ok(java.util.Map.of("id", id, "read", true));
    }

    public record NotificationView(String id, String category, String title, String body,
                                   String resourceType, String resourceId) {
    }
}
