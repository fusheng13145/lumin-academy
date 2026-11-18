package com.leyon.lumin.moderation.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.AppealEvent;
import com.leyon.lumin.foundation.values.AppealState;
import com.leyon.lumin.moderation.domain.AppealStateMachine.TransitionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 申诉状态机全组合穷举（03 §2.4 appeal：两个终态；T24/T25 状态面）。 */
class AppealStateMachineTest {

    @Test
    void fullCombinationMatrixMatchesSpec() {
        int legalCount = 0;
        for (AppealState state : List.of(AppealState.values())) {
            for (AppealEvent event : List.of(AppealEvent.values())) {
                TransitionResult result = AppealStateMachine.transit(state, event);
                boolean legal = (state == AppealState.OPEN
                        && event == AppealEvent.START_REVIEW)
                        || (state == AppealState.IN_REVIEW
                        && (event == AppealEvent.UPHELD || event == AppealEvent.OVERTURNED));
                assertEquals(legal, result.accepted(), state + "+" + event);
                assertTrue(result.auditRequired());
                if (legal) {
                    legalCount++;
                    assertEquals(ErrorCode.OK, result.code());
                    assertTrue(result.ruleId() != null && !result.ruleId().isBlank());
                } else {
                    assertEquals(ErrorCode.B2001, result.code(), state + "+" + event);
                    assertEquals(state, result.targetState());
                }
            }
        }
        assertEquals(3, legalCount);
    }

    @Test
    void appealTerminalsAreUphealdAndOverturned() {
        // 03 §2.4：两个终态；终态后任意迁移拒绝
        for (AppealState terminal : List.of(AppealState.UPHELD, AppealState.OVERTURNED)) {
            for (AppealEvent event : List.of(AppealEvent.values())) {
                assertEquals(ErrorCode.B2001,
                        AppealStateMachine.transit(terminal, event).code(),
                        terminal + "+" + event);
            }
        }
    }

    @Test
    void nullsAreRejected() {
        assertEquals(ErrorCode.B2001,
                AppealStateMachine.transit(null, AppealEvent.START_REVIEW).code());
        assertEquals(ErrorCode.B2001,
                AppealStateMachine.transit(AppealState.OPEN, null).code());
    }

    @Test
    void overturnedDoesNotImplyDirectStateRewrite() {
        // 03 §6：改判不得直接把数据库改成 ACTIVE——状态机仅产出 OVERTURNED 结论
        TransitionResult result = AppealStateMachine.transit(
                AppealState.IN_REVIEW, AppealEvent.OVERTURNED);
        assertEquals(AppealState.OVERTURNED, result.targetState());
    }
}
