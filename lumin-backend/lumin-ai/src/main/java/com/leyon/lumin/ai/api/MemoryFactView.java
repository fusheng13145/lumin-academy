package com.leyon.lumin.ai.api;

import java.time.Instant;

public record MemoryFactView(
        String id,
        String kind,
        String subjectKey,
        String content,
        String status,
        double confidence,
        String sourceVersion,
        Instant createdAt,
        Instant updatedAt
) {
}
