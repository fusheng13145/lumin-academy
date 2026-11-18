package com.leyon.lumin.foundation.values;

/**
 * 审核裁决枚举（契约 MachineReport.suggestedDecision / DecisionRequest.decision、
 * 04 §5 review_decision.decision）。
 *
 * <p>grade 配对规则（05 §4.3）：{@link #requiresGrade()} 为 true 的裁决必须携带 grade，
 * 其余裁决禁止携带 grade（含 null）。
 */
public enum ReviewDecision {
    PASS,
    LABEL,
    LIMIT,
    REVISE,
    HIDE,
    REMOVE,
    BAN,
    ESCALATE;

    /** PASS / LABEL / LIMIT 必填 grade；REVISE / HIDE / REMOVE / BAN / ESCALATE 禁止 grade。 */
    public boolean requiresGrade() {
        return this == PASS || this == LABEL || this == LIMIT;
    }
}
