package com.leyon.lumin.ai.domain;

import java.time.Instant;

/** 记忆变更事件（追加式）；后续可据此重建任意版本的角色/世界观上下文。 */
public record MemoryEvent(
        String id,
        String workId,
        String factId,
        String eventType,
        String beforeJson,
        String afterJson,
        String actorId,
        Instant createdAt
) {
}
