package com.leyon.lumin.ai.domain;

import java.time.Instant;

/** AI 产出的正文候选。作者采纳后才由写作中心写入草稿，AI 不直接改正文。 */
public record AiCandidate(
        String id,
        String runId,
        String content,
        String source,
        AiCandidateState state,
        Instant createdAt
) {
}
