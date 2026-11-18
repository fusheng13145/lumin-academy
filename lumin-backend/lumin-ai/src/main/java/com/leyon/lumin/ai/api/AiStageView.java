package com.leyon.lumin.ai.api;

import java.time.Instant;

public record AiStageView(
        String stageType,
        int attempt,
        String state,
        String output,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
}
