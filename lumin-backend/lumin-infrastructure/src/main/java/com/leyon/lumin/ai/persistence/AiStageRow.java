package com.leyon.lumin.ai.persistence;

import java.time.LocalDateTime;

public record AiStageRow(
        String id,
        String runId,
        String stageType,
        int attempt,
        String state,
        String output,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
