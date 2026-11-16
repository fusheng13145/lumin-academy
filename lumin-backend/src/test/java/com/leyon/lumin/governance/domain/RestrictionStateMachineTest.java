package com.leyon.lumin.governance.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.RestrictionEvent;
import com.leyon.lumin.foundation.values.RestrictionKind;
import com.leyon.lumin.foundation.values.RestrictionState;
import com.leyon.lumin.governance.domain.RestrictionStateMachine.TransitionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 治理措施状态机全组合穷举（03 §2.4 restriction；终局/解约禁止 REVOKED）。 */
class RestrictionStateMachineTest {

    private static final List<RestrictionState> STATES = List.of(RestrictionState.values());
    private static final List<RestrictionEvent> EVENTS = List.of(RestrictionEvent.values());
    private static final List<RestrictionKind> KINDS = List.of(RestrictionKind.values());

    @Test
    void fullCombinationMatrixMatchesSpec() {
        int legalCount = 0;
        for (RestrictionState state : STATES) {
            for (RestrictionEvent event : EVENTS) {
                for (RestrictionKind kind : KINDS) {
                    TransitionResult result =
                            RestrictionStateMachine.transit(state, event, kind);
                    boolean legal = (state == RestrictionState.PENDING
                            && (event == RestrictionEvent.ACTIVATE
                            || event == RestrictionEvent.REJECT))
                            || (state == RestrictionState.ACTIVE
                            && event == RestrictionEvent.REVOKE
                            && (kind == RestrictionKind.TEMPORARY
                            || kind == RestrictionKind.GUARDIAN_REVOKED));
                    assertEquals(legal, result.accepted(),
                            state + "+" + event + "+" + kind);
                    assertTrue(result.auditRequired());
                    if (legal) {
                        legalCount++;
                        assertEquals(ErrorCode.OK, result.code());
                        assertEquals(
                                event == RestrictionEvent.ACTIVATE
                                        ? RestrictionState.ACTIVE
                                        : event == RestrictionEvent.REJECT
                                        ? RestrictionState.REJECTED
                                        : RestrictionState.REVOKED,
                                result.targetState());
                        assertTrue(!result.ruleId().isBlank());
                    } else {
                        assertEquals(ErrorCode.B2001, result.code(),
                                state + "+" + event + "+" + kind);
                        assertEquals(state, result.targetState());
                    }
                }
            }
        }
        // 合法迁移：PENDING+ACTIVATE×4 + PENDING+REJECT×4 + ACTIVE+REVOKE×2 = 10
        assertEquals(10, legalCount);
    }

    @Test
    void finalViolationAndContractTerminatedCannotBeRevoked() {
        // 03 §2.4 / 04 §5：终局/解约禁止 REVOKED——不可恢复传播边界
        for (RestrictionKind kind : List.of(RestrictionKind.FINAL_VIOLATION,
                RestrictionKind.CONTRACT_TERMINATED)) {
            TransitionResult result = RestrictionStateMachine.transit(
                    RestrictionState.ACTIVE, RestrictionEvent.REVOKE, kind);
            assertEquals(ErrorCode.B2001, result.code(), kind.name());
            assertEquals(RestrictionState.ACTIVE, result.targetState());
        }
    }

    @Test
    void terminalStatesAndNullsAreRejected() {
        for (RestrictionEvent event : EVENTS) {
            for (RestrictionKind kind : KINDS) {
                assertEquals(ErrorCode.B2001, RestrictionStateMachine
                        .transit(RestrictionState.REVOKED, event, kind).code());
                assertEquals(ErrorCode.B2001, RestrictionStateMachine
                        .transit(RestrictionState.REJECTED, event, kind).code());
            }
        }
        assertEquals(ErrorCode.B2001, RestrictionStateMachine
                .transit(null, RestrictionEvent.ACTIVATE, RestrictionKind.TEMPORARY).code());
        assertEquals(ErrorCode.B2001, RestrictionStateMachine
                .transit(RestrictionState.PENDING, null, RestrictionKind.TEMPORARY).code());
        assertEquals(ErrorCode.B2001, RestrictionStateMachine
                .transit(RestrictionState.PENDING, RestrictionEvent.ACTIVATE, null).code());
    }
}
