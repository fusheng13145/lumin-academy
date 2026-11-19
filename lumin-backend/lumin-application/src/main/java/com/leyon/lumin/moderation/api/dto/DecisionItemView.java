package com.leyon.lumin.moderation.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 契约 DecisionView：保留首审完整裁决供独立二审比对（05 §3.3）。 */
public record DecisionItemView(
        String id,
        String actorId,
        String decision,
        String grade,
        String reasonCode,
        String reason,
        List<String> evidenceRefs,
        LocalDateTime decidedAt) {
}
