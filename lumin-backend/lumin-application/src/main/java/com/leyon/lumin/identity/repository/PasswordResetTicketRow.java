package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/**
 * 口令重置工单行（V24）。
 *
 * <p>{@code codeHash} 为一次性重置码的 SHA-256 摘要（明文只在投递/口述时出现一次，不落库）；
 * 状态机：{@code AWAITING_OPERATOR}（客服通道待人工核验）→ {@code PENDING}（已有码待确认）
 * → {@code CONSUMED}；旁支 {@code DELIVERY_FAILED}（邮件通道不可用）/ {@code EXPIRED}
 * / {@code REJECTED}。
 */
public record PasswordResetTicketRow(
        String id,
        String loginId,
        String subject,
        String channel,
        String state,
        String codeHash,
        LocalDateTime expiresAt,
        LocalDateTime consumedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
