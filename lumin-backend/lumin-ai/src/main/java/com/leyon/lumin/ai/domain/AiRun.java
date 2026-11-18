package com.leyon.lumin.ai.domain;

import java.time.Instant;

/** 一次 AI 运行的稳定记录；正文候选单独存放在 ai_candidate。 */
public record AiRun(
        String id,
        String workId,
        String chapterId,
        AiMode mode,
        AiRunState state,
        String instruction,
        String provider,
        String model,
        String memoryRevision,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
