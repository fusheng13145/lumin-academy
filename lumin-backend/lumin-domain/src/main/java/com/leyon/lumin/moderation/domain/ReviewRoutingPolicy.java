package com.leyon.lumin.moderation.domain;

import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.values.CaseSource;
import com.leyon.lumin.foundation.values.RiskFlag;
import com.leyon.lumin.foundation.values.RiskLevel;
import com.leyon.lumin.foundation.values.ReviewDecision;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 机审路由纯函数（03 §5.1 节点 N4 / §5.3 分级规则；O14b 分级放行）。
 *
 * <p>判定顺序固定、必人审优先：①证据缺失 HOLD；②必人审三条件（作者年龄层 ≠ C3、
 * 风险含 MINOR / ILLEGAL / MONEY_DISPUTE、来源 APPEAL）按逻辑 OR；③HIGH 双人、
 * MEDIUM 人工单审并 20% 质检；④HIDE / REMOVE / BAN 不自动作为终局治理裁决、
 * ESCALATE 必转人工；⑤LOW 且 confidence ≥ 0.98、校准有效才自动
 * （PASS / LABEL / LIMIT → AUTO_ACCEPT，REVISE → AUTO_REVISE）。
 *
 * <p>本函数只产出路由结论，不执行状态迁移、不写公开指针（N5：核心域状态机执行，
 * Agent 不写公开指针）；K-03 自动放行校准门禁（T48）通过前 calibrationValid
 * 必须为 false，即全部转人工。
 */
public final class ReviewRoutingPolicy {

    /** LOW 自动放行置信度阈值（03 §5.3：confidence ≥ 0.98 → 9800bp；UGC-008 起由配置覆盖）。 */
    public static final int AUTO_CONFIDENCE_THRESHOLD_BASIS_POINTS = 9800;
    /** 旧口径常量（保留既有引用）。 */
    public static final java.math.BigDecimal AUTO_CONFIDENCE_THRESHOLD = new BigDecimal("0.98");

    /** 路由结论（03 §5.1 N4 输出：AUTO_ACCEPT / AUTO_REVISE / HUMAN / HOLD）。 */
    public enum RoutingOutcome {
        AUTO_ACCEPT,
        AUTO_REVISE,
        HUMAN,
        HOLD
    }

    /** 路由输入：全部取自权威记录与已通过契约校验的机审报告（INV-07 / INV-08）；
     *  置信阈值（bp）由 REVIEW_ROUTING 配置解析注入（UGC-008 配置驱动路由）。 */
    public record RoutingInput(boolean machineReviewComplete, boolean calibrationValid,
                               AgeBand authorAgeBand, CaseSource source, RiskLevel risk,
                               Set<RiskFlag> riskFlags, BigDecimal confidence,
                               ReviewDecision suggestedDecision,
                               int autoConfidenceThresholdBp) {
    }

    /** 路由结论：outcome + 命中规则标识（供裁决记录与审计定位）。 */
    public record RoutingDecision(RoutingOutcome outcome, String ruleId) {
    }

    // 命中规则标识（集中定义，禁止散落字面量）
    public static final String HOLD_MACHINE_REVIEW_INCOMPLETE = "HOLD_MACHINE_REVIEW_INCOMPLETE";
    public static final String HOLD_MISSING_EVIDENCE = "HOLD_MISSING_EVIDENCE";
    public static final String HUMAN_AUTHOR_AGE_BAND = "HUMAN_AUTHOR_AGE_BAND";
    public static final String HUMAN_PROTECTED_RISK_FLAG = "HUMAN_PROTECTED_RISK_FLAG";
    public static final String HUMAN_APPEAL_SOURCE = "HUMAN_APPEAL_SOURCE";
    public static final String HUMAN_HIGH_RISK_DOUBLE_REVIEW = "HUMAN_HIGH_RISK_DOUBLE_REVIEW";
    public static final String HUMAN_MEDIUM_RISK_QC = "HUMAN_MEDIUM_RISK_QC";
    public static final String HUMAN_TERMINAL_DECISION_FORBIDDEN_AUTO =
            "HUMAN_TERMINAL_DECISION_FORBIDDEN_AUTO";
    public static final String HUMAN_ESCALATION = "HUMAN_ESCALATION";
    public static final String HUMAN_LOW_CONFIDENCE = "HUMAN_LOW_CONFIDENCE";
    public static final String HUMAN_CALIBRATION_INVALID = "HUMAN_CALIBRATION_INVALID";
    public static final String AUTO_ACCEPT_LOW_RISK = "AUTO_ACCEPT_LOW_RISK";
    public static final String AUTO_REVISE_LOW_RISK = "AUTO_REVISE_LOW_RISK";
    public static final String HUMAN_UNROUTED = "HUMAN_UNROUTED";

    private ReviewRoutingPolicy() {
    }

    /** 默认阈值路由（9800bp = 0.98，03 §5.3 原始口径；保留供既有调用与测试）。 */
    public static RoutingDecision route(RoutingInput input) {
        return route(input, AUTO_CONFIDENCE_THRESHOLD_BASIS_POINTS);
    }

    /** 配置驱动路由（UGC-008）：置信阈值由 REVIEW_ROUTING ACTIVE 修订解析（bp）。 */
    public static RoutingDecision route(RoutingInput input, int autoConfidenceThresholdBp) {
        // ① 证据缺失：机审未完成（INV-08：机审未完成不得发布）或权威年龄缺失
        if (input == null || !input.machineReviewComplete()) {
            return new RoutingDecision(RoutingOutcome.HOLD, HOLD_MACHINE_REVIEW_INCOMPLETE);
        }
        if (input.authorAgeBand() == null || input.risk() == null
                || input.suggestedDecision() == null || input.confidence() == null) {
            return new RoutingDecision(RoutingOutcome.HOLD, HOLD_MISSING_EVIDENCE);
        }

        // ② 必人审条件（逻辑 OR，任何一项即人工，03 §5.3）
        if (input.authorAgeBand() != AgeBand.C3) {
            return new RoutingDecision(RoutingOutcome.HUMAN, HUMAN_AUTHOR_AGE_BAND);
        }
        Set<RiskFlag> flags = input.riskFlags() == null ? Set.of() : input.riskFlags();
        if (flags.contains(RiskFlag.MINOR) || flags.contains(RiskFlag.ILLEGAL)
                || flags.contains(RiskFlag.MONEY_DISPUTE)) {
            return new RoutingDecision(RoutingOutcome.HUMAN, HUMAN_PROTECTED_RISK_FLAG);
        }
        if (input.source() == CaseSource.APPEAL) {
            return new RoutingDecision(RoutingOutcome.HUMAN, HUMAN_APPEAL_SOURCE);
        }

        // ③ 风险分级
        if (input.risk() == RiskLevel.HIGH) {
            return new RoutingDecision(RoutingOutcome.HUMAN, HUMAN_HIGH_RISK_DOUBLE_REVIEW);
        }
        if (input.risk() == RiskLevel.MEDIUM) {
            return new RoutingDecision(RoutingOutcome.HUMAN, HUMAN_MEDIUM_RISK_QC);
        }

        // ④ 终局治理裁决与升级不得自动执行（03 §5.3）
        if (input.suggestedDecision() == ReviewDecision.HIDE
                || input.suggestedDecision() == ReviewDecision.REMOVE
                || input.suggestedDecision() == ReviewDecision.BAN) {
            return new RoutingDecision(RoutingOutcome.HUMAN, HUMAN_TERMINAL_DECISION_FORBIDDEN_AUTO);
        }
        if (input.suggestedDecision() == ReviewDecision.ESCALATE) {
            return new RoutingDecision(RoutingOutcome.HUMAN, HUMAN_ESCALATION);
        }

        // ⑤ LOW 且低置信 / 校准无效 → 人工；全部满足才自动
        if (input.risk() != RiskLevel.LOW) {
            return new RoutingDecision(RoutingOutcome.HUMAN, HUMAN_UNROUTED);
        }
        if (input.confidence().movePointRight(4).intValue()
                < autoConfidenceThresholdBp) {
            return new RoutingDecision(RoutingOutcome.HUMAN, HUMAN_LOW_CONFIDENCE);
        }
        if (!input.calibrationValid()) {
            return new RoutingDecision(RoutingOutcome.HUMAN, HUMAN_CALIBRATION_INVALID);
        }
        if (input.suggestedDecision() == ReviewDecision.REVISE) {
            return new RoutingDecision(RoutingOutcome.AUTO_REVISE, AUTO_REVISE_LOW_RISK);
        }
        if (input.suggestedDecision() == ReviewDecision.PASS
                || input.suggestedDecision() == ReviewDecision.LABEL
                || input.suggestedDecision() == ReviewDecision.LIMIT) {
            return new RoutingDecision(RoutingOutcome.AUTO_ACCEPT, AUTO_ACCEPT_LOW_RISK);
        }
        return new RoutingDecision(RoutingOutcome.HUMAN, HUMAN_UNROUTED);
    }
}
