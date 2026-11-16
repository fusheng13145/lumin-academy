package com.leyon.lumin.moderation.repository;

import java.time.LocalDateTime;

/** appeal 表行记录（docs/04 §5 moderation）。 */
public record AppealRow(
        String id,
        String caseId,
        String applicantId,
        String reason,
        String evidenceRefs,
        String reviewerId,
        String state,
        String decisionReason,
        LocalDateTime acceptedDueAt,
        LocalDateTime completedDueAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
