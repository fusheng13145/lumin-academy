package com.leyon.lumin.engagement.repository;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * subscription 表行记录（docs/04 §6.1 + timezone_id 扩展列，V17）。
 * 字段顺序与表列序一致（SELECT * 位置读取）。
 */
public record SubscriptionRow(
        String id,
        String userId,
        String workId,
        Boolean enabled,
        LocalTime quietStart,
        LocalTime quietEnd,
        Boolean pushEnabled,
        String timezoneId,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy,
        Long lockVersion) {
}
