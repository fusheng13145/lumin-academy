package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/** 会话令牌行（UGC-002；token_hash=SHA-256，原始令牌不落库；revoked_at 撤销留痕）。 */
public record AuthSessionRow(
        String id,
        String tokenHash,
        String subject,
        String stubName,
        String roleContext,
        LocalDateTime issuedAt,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
