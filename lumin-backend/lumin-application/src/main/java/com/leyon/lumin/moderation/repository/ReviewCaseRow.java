package com.leyon.lumin.moderation.repository;

import java.time.LocalDateTime;

/** review_case 表行记录（docs/04 §5 moderation）。 */
public record ReviewCaseRow(
        String id,
        String versionId,
        String workflowId,
        String workId,
        String chapterId,
        String source,
        String risk,
        String priority,
        String state,
        String queueId,
        Integer reviewStage,
        String assigneeId,
        LocalDateTime leaseExpiresAt,
        LocalDateTime responseDueAt,
        LocalDateTime completionDueAt,
        Long policyVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion,
        LocalDateTime deletedAt) {
}
