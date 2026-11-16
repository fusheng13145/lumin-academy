package com.leyon.lumin.foundation.repository;

import java.time.LocalDateTime;

/** outbox_event 表行记录（docs/04 §5.1；payload 为规范化 JSON 串；INV-06 同事务提交由服务层保证）。 */
public record OutboxEventRow(
        String id,
        String eventId,
        String aggregateType,
        String aggregateId,
        Long aggregateRevision,
        String eventType,
        Integer schemaVersion,
        String payload,
        LocalDateTime occurredAt,
        LocalDateTime deliveredAt,
        Integer attempts,
        LocalDateTime nextAttemptAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
