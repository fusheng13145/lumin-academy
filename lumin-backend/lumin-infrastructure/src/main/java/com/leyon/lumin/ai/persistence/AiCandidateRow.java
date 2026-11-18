package com.leyon.lumin.ai.persistence;

import java.time.LocalDateTime;

public record AiCandidateRow(
        String id,
        String runId,
        String content,
        String source,
        String state,
        LocalDateTime createdAt
) {
}
