package com.leyon.lumin.moderation.repository;

import java.time.LocalDateTime;

/** review_decision 表行记录（docs/04 §5；append-only，无 deleted_at）。 */
public record ReviewDecisionRow(
        String id,
        String caseId,
        String actorId,
        String actorType,
        String decision,
        String grade,
        String reasonCode,
        String reason,
        String evidenceRefs,
        Integer stage,
        Long policyVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
