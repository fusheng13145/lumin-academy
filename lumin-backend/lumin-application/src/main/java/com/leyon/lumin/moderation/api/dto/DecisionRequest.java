package com.leyon.lumin.moderation.api.dto;

import java.util.List;

/**
 * 裁决载荷（05 §3.3 decideCase）：理由/证据必填；PASS/LABEL/LIMIT 必带 grade、
 * 其余禁止 grade（ReviewDecisionRule）；HIDE/REMOVE/BAN 为治理语义（骨架登记）。
 */
public record DecisionRequest(
        String decision,
        String grade,
        String reasonCode,
        String reason,
        List<String> evidenceRefs) {
}
