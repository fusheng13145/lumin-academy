package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/** 注销推进状态行（03 §8：注销立即断开公开关联；6 个月后匿名化内部关联）。 */
public record AccountClosureRow(
        String id,
        String userId,
        String state,
        LocalDateTime requestedAt,
        LocalDateTime anonymizeDueAt,
        String holdId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
