package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/**
 * 二次验证解绑工单行（V23）。
 *
 * <p>工单只能由 OPERATOR/ADMIN 裁决：申请人本人不能解绑（否则会话劫持者可直接摘掉第二因子）。
 * {@code openSlot} 由数据库生成列保证"同一主体同时只有一张 PENDING 工单"。
 */
public record MfaUnbindRequestRow(
        String id,
        String subject,
        String reason,
        String state,
        LocalDateTime requestedAt,
        String reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
