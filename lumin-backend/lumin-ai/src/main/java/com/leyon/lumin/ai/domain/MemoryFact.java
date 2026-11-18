package com.leyon.lumin.ai.domain;

import java.time.Instant;

/** 长期记忆事实；subjectKey 用于人物名/地名/伏笔编号等稳定检索。 */
public record MemoryFact(
        String id,
        String workId,
        MemoryKind kind,
        String subjectKey,
        String content,
        MemoryStatus status,
        double confidence,
        String sourceVersion,
        Instant createdAt,
        Instant updatedAt
) {
}
