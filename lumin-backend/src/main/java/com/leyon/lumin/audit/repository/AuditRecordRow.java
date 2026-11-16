package com.leyon.lumin.audit.repository;

import java.time.LocalDateTime;

/** audit_record 表行记录（docs/04 §5.1 audit；append-only，不记正文/令牌/证件）。 */
public record AuditRecordRow(
        String id,
        String actorId,
        String roleContext,
        String action,
        String resourceType,
        String resourceId,
        String beforeRedacted,
        String afterRedacted,
        String reason,
        String traceId,
        LocalDateTime eventAt,
        String digest,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
