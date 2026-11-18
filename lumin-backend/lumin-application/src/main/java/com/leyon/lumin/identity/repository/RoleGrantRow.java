package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/** role_grant 表行记录（docs/04 §5.2 identity；requested_by≠reviewed_by，互斥由 RolePolicy）。 */
public record RoleGrantRow(
        String id,
        String targetUserId,
        String role,
        String scopeType,
        String scopeId,
        LocalDateTime expiresAt,
        String reason,
        String state,
        String requestedBy,
        String reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
