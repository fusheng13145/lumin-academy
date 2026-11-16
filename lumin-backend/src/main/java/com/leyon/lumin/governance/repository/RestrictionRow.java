package com.leyon.lumin.governance.repository;

import java.time.LocalDateTime;

/** restriction 表行记录（docs/04 §5 governance）。 */
public record RestrictionRow(
        String id,
        String workId,
        String chapterId,
        String kind,
        String state,
        String reason,
        String caseId,
        String requestedBy,
        String reviewedBy,
        LocalDateTime effectiveAt,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt,
        String appealId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
