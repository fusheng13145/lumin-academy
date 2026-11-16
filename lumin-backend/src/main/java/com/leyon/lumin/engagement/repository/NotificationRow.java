package com.leyon.lumin.engagement.repository;

import java.time.LocalDateTime;

/** notification 表行记录（docs/04 §5.1 engagement；U(recipient,source_event,category)）。 */
public record NotificationRow(
        String id,
        String recipientId,
        String category,
        String sourceEventId,
        String title,
        String body,
        String resourceType,
        String resourceId,
        LocalDateTime readAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
