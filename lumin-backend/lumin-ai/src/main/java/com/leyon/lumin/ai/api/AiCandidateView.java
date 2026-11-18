package com.leyon.lumin.ai.api;

import java.time.Instant;

public record AiCandidateView(
        String id,
        String content,
        String source,
        String state,
        Instant createdAt
) {
}
