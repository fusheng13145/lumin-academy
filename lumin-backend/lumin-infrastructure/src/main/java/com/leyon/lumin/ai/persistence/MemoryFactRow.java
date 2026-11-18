package com.leyon.lumin.ai.persistence;

import java.time.LocalDateTime;

public record MemoryFactRow(
        String id,
        String workId,
        String kind,
        String subjectKey,
        String content,
        String status,
        double confidence,
        String sourceVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
