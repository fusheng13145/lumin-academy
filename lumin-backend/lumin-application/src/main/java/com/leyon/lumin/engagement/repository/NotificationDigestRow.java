package com.leyon.lumin.engagement.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * notification_digest 表行记录（docs/04 §6.1；V17）。
 * 字段顺序与表列序一致（SELECT * 位置读取）。
 */
public record NotificationDigestRow(
        String id,
        String recipientId,
        String workId,
        LocalDate localDate,
        String latestChapterId,
        LocalDateTime sentAt,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy,
        Long lockVersion) {
}
