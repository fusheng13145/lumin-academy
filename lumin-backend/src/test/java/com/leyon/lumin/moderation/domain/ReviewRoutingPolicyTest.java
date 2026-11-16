package com.leyon.lumin.moderation.domain;

import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.values.CaseSource;
import com.leyon.lumin.foundation.values.RiskFlag;
import com.leyon.lumin.foundation.values.RiskLevel;
import com.leyon.lumin.foundation.values.ReviewDecision;
import com.leyon.lumin.moderation.domain.ReviewRoutingPolicy.RoutingDecision;
import com.leyon.lumin.moderation.domain.ReviewRoutingPolicy.RoutingInput;
import com.leyon.lumin.moderation.domain.ReviewRoutingPolicy.RoutingOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 机审路由与分级规则（T16 / T17：阈值内外精确、必人审不可被覆盖；
 * 03 §5.1 N4 / §5.3；K-03 校准未过时全转人工）。
 */
class ReviewRoutingPolicyTest {

    private static RoutingInput input(AgeBand authorAgeBand, CaseSource source,
                                      RiskLevel risk, Set<RiskFlag> flags,
                                      String confidence, ReviewDecision suggested,
                                      boolean machineReviewComplete, boolean calibrationValid) {
        return new RoutingInput(machineReviewComplete, calibrationValid, authorAgeBand,
                source, risk, flags,
                confidence == null ? null : new BigDecimal(confidence), suggested);
    }

    private static RoutingInput base() {
        return input(AgeBand.C3, CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(),
                "1", ReviewDecision.PASS, true, true);
    }

    @Test
    void t16ConfidenceThresholdIsExact() {
        // 阈值内 0.9799 → 人工；0.98 与 1 → 自动（C3 + LOW）
        assertEquals(RoutingOutcome.HUMAN, ReviewRoutingPolicy.route(
                input(AgeBand.C3, CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(),
                        "0.9799", ReviewDecision.PASS, true, true)).outcome());
        assertEquals(RoutingOutcome.AUTO_ACCEPT, ReviewRoutingPolicy.route(
                input(AgeBand.C3, CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(),
                        "0.98", ReviewDecision.PASS, true, true)).outcome());
        assertEquals(RoutingOutcome.AUTO_ACCEPT, ReviewRoutingPolicy.route(base()).outcome());
        // MEDIUM / HIGH 进人工
        RoutingDecision medium = ReviewRoutingPolicy.route(input(AgeBand.C3,
                CaseSource.SUBMISSION, RiskLevel.MEDIUM, Set.of(), "1",
                ReviewDecision.PASS, true, true));
        assertEquals(RoutingOutcome.HUMAN, medium.outcome());
        assertEquals(ReviewRoutingPolicy.HUMAN_MEDIUM_RISK_QC, medium.ruleId());
        RoutingDecision high = ReviewRoutingPolicy.route(input(AgeBand.C3,
                CaseSource.SUBMISSION, RiskLevel.HIGH, Set.of(), "1",
                ReviewDecision.PASS, true, true));
        assertEquals(RoutingOutcome.HUMAN, high.outcome());
        assertEquals(ReviewRoutingPolicy.HUMAN_HIGH_RISK_DOUBLE_REVIEW, high.ruleId());
    }

    @Test
    void t17ProtectedConditionsCannotBeOverriddenByHighConfidence() {
        // 4 类必人审条件逐个 + 置信度 1：全部人工
        for (RiskFlag flag : Set.of(RiskFlag.MINOR, RiskFlag.ILLEGAL,
                RiskFlag.MONEY_DISPUTE)) {
            RoutingDecision d = ReviewRoutingPolicy.route(input(AgeBand.C3,
                    CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(flag), "1",
                    ReviewDecision.PASS, true, true));
            assertEquals(RoutingOutcome.HUMAN, d.outcome(), flag.name());
            assertEquals(ReviewRoutingPolicy.HUMAN_PROTECTED_RISK_FLAG, d.ruleId());
        }
        RoutingDecision appeal = ReviewRoutingPolicy.route(input(AgeBand.C3,
                CaseSource.APPEAL, RiskLevel.LOW, Set.of(), "1",
                ReviewDecision.PASS, true, true));
        assertEquals(RoutingOutcome.HUMAN, appeal.outcome());
        assertEquals(ReviewRoutingPolicy.HUMAN_APPEAL_SOURCE, appeal.ruleId());
        // 作者年龄层 ≠ C3（未成年或未核验）：必人审，即使报告全绿
        for (AgeBand band : Set.of(AgeBand.C0, AgeBand.C1, AgeBand.C2, AgeBand.C4)) {
            RoutingDecision d = ReviewRoutingPolicy.route(input(band,
                    CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(), "1",
                    ReviewDecision.PASS, true, true));
            assertEquals(RoutingOutcome.HUMAN, d.outcome(), band.name());
            assertEquals(ReviewRoutingPolicy.HUMAN_AUTHOR_AGE_BAND, d.ruleId());
        }
    }

    @Test
    void terminalGovernanceDecisionsAreNeverAutomatic() {
        for (ReviewDecision decision : Set.of(ReviewDecision.HIDE,
                ReviewDecision.REMOVE, ReviewDecision.BAN)) {
            RoutingDecision d = ReviewRoutingPolicy.route(input(AgeBand.C3,
                    CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(), "1", decision,
                    true, true));
            assertEquals(RoutingOutcome.HUMAN, d.outcome(), decision.name());
            assertEquals(ReviewRoutingPolicy.HUMAN_TERMINAL_DECISION_FORBIDDEN_AUTO,
                    d.ruleId());
        }
        RoutingDecision escalate = ReviewRoutingPolicy.route(input(AgeBand.C3,
                CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(), "1",
                ReviewDecision.ESCALATE, true, true));
        assertEquals(RoutingOutcome.HUMAN, escalate.outcome());
        assertEquals(ReviewRoutingPolicy.HUMAN_ESCALATION, escalate.ruleId());
    }

    @Test
    void lowRiskAutoReviseAndHoldPaths() {
        RoutingDecision autoRevise = ReviewRoutingPolicy.route(input(AgeBand.C3,
                CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(), "0.98",
                ReviewDecision.REVISE, true, true));
        assertEquals(RoutingOutcome.AUTO_REVISE, autoRevise.outcome());
        assertEquals(ReviewRoutingPolicy.AUTO_REVISE_LOW_RISK, autoRevise.ruleId());
        for (ReviewDecision decision : Set.of(ReviewDecision.PASS, ReviewDecision.LABEL,
                ReviewDecision.LIMIT)) {
            assertEquals(RoutingOutcome.AUTO_ACCEPT, ReviewRoutingPolicy.route(input(
                    AgeBand.C3, CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(), "0.99",
                    decision, true, true)).outcome(), decision.name());
        }
        // 机审未完成：HOLD（INV-08）
        assertEquals(RoutingOutcome.HOLD, ReviewRoutingPolicy.route(
                input(AgeBand.C3, CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(), "1",
                        ReviewDecision.PASS, false, true)).outcome());
        // 缺证据字段：HOLD（N4 缺任何证据 HOLD）
        assertEquals(RoutingOutcome.HOLD, ReviewRoutingPolicy.route(
                input(null, CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(), "1",
                        ReviewDecision.PASS, true, true)).outcome());
        assertEquals(RoutingOutcome.HOLD, ReviewRoutingPolicy.route(
                input(AgeBand.C3, CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(), null,
                        ReviewDecision.PASS, true, true)).outcome());
        assertEquals(RoutingOutcome.HOLD, ReviewRoutingPolicy.route(null).outcome());
        // 校准无效：全转人工（K-03 门禁未过时 calibrationValid=false）
        RoutingDecision uncalibrated = ReviewRoutingPolicy.route(
                input(AgeBand.C3, CaseSource.SUBMISSION, RiskLevel.LOW, Set.of(), "1",
                        ReviewDecision.PASS, true, false));
        assertEquals(RoutingOutcome.HUMAN, uncalibrated.outcome());
        assertEquals(ReviewRoutingPolicy.HUMAN_CALIBRATION_INVALID, uncalibrated.ruleId());
    }

    @Test
    void ruleIdsAreDeterministicPerPath() {
        // 同输入同结论：路由纯函数无随机性
        RoutingDecision a = ReviewRoutingPolicy.route(base());
        RoutingDecision b = ReviewRoutingPolicy.route(base());
        assertEquals(a, b);
        // 不同路径结论不同，ruleId 可定位
        assertNotEquals(ReviewRoutingPolicy.route(input(AgeBand.C3, CaseSource.APPEAL,
                RiskLevel.LOW, Set.of(), "1", ReviewDecision.PASS, true, true)),
                ReviewRoutingPolicy.route(input(AgeBand.C2, CaseSource.SUBMISSION,
                        RiskLevel.LOW, Set.of(), "1", ReviewDecision.PASS, true, true)));
    }
}
