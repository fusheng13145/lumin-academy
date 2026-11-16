package com.leyon.lumin.publication.persistence;

import java.time.LocalDateTime;

/** publication_workflow 表行记录（docs/04 §4；SUSPENDED⇔suspended_reason 一致性由 CHECK 兜底）。 */
public record PublicationWorkflowRow(
        String id,
        String versionId,
        String targetType,
        String targetId,
        String state,
        Long policyVersion,
        String caseId,
        String suspendedReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
