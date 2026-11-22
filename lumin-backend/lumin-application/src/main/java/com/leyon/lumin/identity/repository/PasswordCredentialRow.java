package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/**
 * 口令凭据行（V20；2026-11-20 安全审查 05 项）。
 * {@code password_hash} 仅存 BCrypt 摘要（不可逆、不存明文）；失败计数与锁定时间
 * 落库，保证多实例/重启后锁定状态一致。
 */
public record PasswordCredentialRow(
        String id,
        String subject,
        String loginId,
        String passwordHash,
        LocalDateTime passwordUpdatedAt,
        int failedCount,
        LocalDateTime lockedUntil,
        LocalDateTime lastLoginAt,
        String state,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
