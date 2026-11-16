package com.leyon.lumin.moderation.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.ReviewCaseEvent;
import com.leyon.lumin.foundation.values.ReviewCaseState;
import com.leyon.lumin.moderation.domain.ReviewCaseStateMachine.TransitionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核案件状态机全组合穷举（03 §2.4 review_case；T20/T21 状态面：
 * 每状态 × 每事件 × 阶段 1–3，未列迁移一律 B2001）。
 */
class ReviewCaseStateMachineTest {

    private static final List<ReviewCaseState> STATES =
            List.of(ReviewCaseState.values());
    private static final List<ReviewCaseEvent> EVENTS =
            List.of(ReviewCaseEvent.values());

    @Test
    void fullCombinationMatrixMatchesSpec() {
        int legalCount = 0;
        for (ReviewCaseState state : STATES) {
            for (ReviewCaseEvent event : EVENTS) {
                for (int stage = 1; stage <= 3; stage++) {
                    TransitionResult result =
                            ReviewCaseStateMachine.transit(state, event, stage);
                    Expected expected = expectedOf(state, event, stage);
                    assertEquals(expected.legal(), result.accepted(),
                            state + "+" + event + "+stage" + stage);
                    if (expected.legal()) {
                        legalCount++;
                        assertEquals(ErrorCode.OK, result.code());
                        assertEquals(expected.targetState(), result.targetState(),
                                state + "+" + event + "+stage" + stage);
                        assertEquals(expected.targetStage(stage), result.targetStage(),
                                state + "+" + event + "+stage" + stage);
                        assertTrue(result.auditRequired());
                        assertTrue(result.ruleId() != null && !result.ruleId().isBlank());
                    } else {
                        assertEquals(ErrorCode.B2001, result.code(),
                                state + "+" + event + "+stage" + stage);
                        assertEquals(state, result.targetState());
                        assertEquals(stage, result.targetStage());
                        assertTrue(result.auditRequired());
                    }
                }
            }
        }
        // 合法迁移数：QUEUED(2 事件×3 阶段) + CLAIMED(CLAIM_LEASE×3 + FIRST_DECISION×1
        //   + SPLIT×2 + ESCALATE×2 + DECIDE×3) + WAITING_SECOND(CLAIM×3) = 6+11+3 = 20
        assertEquals(20, legalCount);
    }

    /** 规格期望表（03 §2.4 review_case 合法迁移 + 阶段语义）。 */
    private static Expected expectedOf(ReviewCaseState state, ReviewCaseEvent event,
                                       int stage) {
        // (state, event) → 目标态；阶段规则由 targetStage(stage) 计算
        Map<ReviewCaseEvent, ReviewCaseState> fromQueued = Map.of(
                ReviewCaseEvent.CLAIM, ReviewCaseState.CLAIMED,
                ReviewCaseEvent.AUTO_DECIDE, ReviewCaseState.DECIDED);
        Map<ReviewCaseEvent, ReviewCaseState> fromClaimed = Map.of(
                ReviewCaseEvent.CLAIM_LEASE_EXPIRED, ReviewCaseState.QUEUED,
                ReviewCaseEvent.FIRST_DECISION, ReviewCaseState.WAITING_SECOND,
                ReviewCaseEvent.SECOND_LEASE_EXPIRED_OR_SPLIT, ReviewCaseState.WAITING_SECOND,
                ReviewCaseEvent.ESCALATE, ReviewCaseState.WAITING_SECOND,
                ReviewCaseEvent.DECIDE, ReviewCaseState.DECIDED);
        Map<ReviewCaseEvent, ReviewCaseState> fromWaiting = Map.of(
                ReviewCaseEvent.CLAIM, ReviewCaseState.CLAIMED);

        return switch (state) {
            case QUEUED -> fromQueued.containsKey(event)
                    ? Expected.legal(fromQueued.get(event), s -> s)
                    : Expected.illegal();
            case CLAIMED -> {
                if (event == ReviewCaseEvent.FIRST_DECISION) {
                    yield stage == 1
                            ? Expected.legal(ReviewCaseState.WAITING_SECOND, s -> 2)
                            : Expected.illegal();
                }
                if (event == ReviewCaseEvent.SECOND_LEASE_EXPIRED_OR_SPLIT) {
                    yield stage >= 2
                            ? Expected.legal(ReviewCaseState.WAITING_SECOND, s -> 3)
                            : Expected.illegal();
                }
                if (event == ReviewCaseEvent.ESCALATE) {
                    yield stage < 3
                            ? Expected.legal(ReviewCaseState.WAITING_SECOND, s -> 3)
                            : Expected.illegal();
                }
                yield fromClaimed.containsKey(event)
                        ? Expected.legal(fromClaimed.get(event), s -> s)
                        : Expected.illegal();
            }
            case WAITING_SECOND -> fromWaiting.containsKey(event)
                    ? Expected.legal(fromWaiting.get(event), s -> s)
                    : Expected.illegal();
            case DECIDED -> Expected.illegal();
        };
    }

    /** 阶段 3 租约超时保持 3；其余目标阶段见 lambda。 */
    private record Expected(boolean legal, ReviewCaseState targetState,
                            java.util.function.IntUnaryOperator targetStage) {
        static Expected legal(ReviewCaseState target,
                              java.util.function.IntUnaryOperator stage) {
            return new Expected(true, target, stage);
        }

        static Expected illegal() {
            return new Expected(false, null, s -> s);
        }

        int targetStage(int current) {
            return legal ? targetStage.applyAsInt(current) : current;
        }
    }

    @Test
    void invalidStageInputIsRejected() {
        for (ReviewCaseEvent event : EVENTS) {
            assertEquals(ErrorCode.B2001,
                    ReviewCaseStateMachine.transit(ReviewCaseState.QUEUED, event, 0).code());
            assertEquals(ErrorCode.B2001,
                    ReviewCaseStateMachine.transit(ReviewCaseState.QUEUED, event, 4).code());
        }
        assertEquals(ErrorCode.B2001,
                ReviewCaseStateMachine.transit(null, ReviewCaseEvent.CLAIM, 1).code());
        assertEquals(ErrorCode.B2001,
                ReviewCaseStateMachine.transit(ReviewCaseState.QUEUED, null, 1).code());
    }

    @Test
    void decidedIsTerminalAndEscalateOnlyFromClaimed() {
        for (ReviewCaseEvent event : EVENTS) {
            for (int stage = 1; stage <= 3; stage++) {
                TransitionResult result =
                        ReviewCaseStateMachine.transit(ReviewCaseState.DECIDED, event, stage);
                assertEquals(ErrorCode.B2001, result.code());
                assertNotEquals(ReviewCaseState.CLAIMED, result.targetState());
            }
        }
        // ESCALATE 仅自 CLAIMED（处理中升级）
        assertEquals(ErrorCode.B2001, ReviewCaseStateMachine
                .transit(ReviewCaseState.QUEUED, ReviewCaseEvent.ESCALATE, 1).code());
        assertEquals(ReviewCaseState.WAITING_SECOND, ReviewCaseStateMachine
                .transit(ReviewCaseState.CLAIMED, ReviewCaseEvent.ESCALATE, 2).targetState());
        // 阶段 3 再 ESCALATE 拒绝（已仲裁）
        assertEquals(ErrorCode.B2001, ReviewCaseStateMachine
                .transit(ReviewCaseState.CLAIMED, ReviewCaseEvent.ESCALATE, 3).code());
    }
}
