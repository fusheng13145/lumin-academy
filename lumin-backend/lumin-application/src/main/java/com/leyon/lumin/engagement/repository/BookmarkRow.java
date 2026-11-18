package com.leyon.lumin.engagement.repository;

import java.time.LocalDateTime;

/** 收藏行记录（docs/04 §6.1 bookmark；U(user_id,work_id)；无通知语义）。 */
public record BookmarkRow(
        String id,
        String userId,
        String workId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
