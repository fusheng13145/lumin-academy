package com.leyon.lumin.ai.domain;

import java.time.Instant;

/** workflow 的单阶段执行记录；attempt 用于表达“一致性检查后的有限修订”。 */
public record AiStage(
        String id,
        String runId,
        AiStageType stageType,
        int attempt,
        AiStageState state,
        String output,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
}
