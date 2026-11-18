package com.leyon.lumin.ai.persistence;

import java.time.LocalDateTime;

public record MemoryEventRow(
        String id,
        String workId,
        String factId,
        String eventType,
        String beforeJson,
        String afterJson,
        String actorId,
        LocalDateTime createdAt
) {
}
