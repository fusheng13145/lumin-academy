package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/** proposal_review 表行记录（docs/04 §5.2；按 resource_type/resource_id 追加追溯）。 */
public record ProposalReviewRow(
        String id,
        String resourceType,
        String resourceId,
        String actorId,
        String action,
        String reason,
        String evidenceRefsJson,
        Long reviewedRevision,
        LocalDateTime eventAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
