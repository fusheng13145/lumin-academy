package com.leyon.lumin.ai.api;

import java.time.Instant;
import java.util.List;

/** 运行详情视图；stages 用于展示“计划→起草→一致性→记忆抽取”。 */
public record AiRunView(
        String id,
        String workId,
        String chapterId,
        String mode,
        String state,
        String provider,
        String model,
        String memoryRevision,
        String errorMessage,
        List<AiStageView> stages,
        List<AiCandidateView> candidates,
        Instant createdAt,
        Instant updatedAt
) {
}
