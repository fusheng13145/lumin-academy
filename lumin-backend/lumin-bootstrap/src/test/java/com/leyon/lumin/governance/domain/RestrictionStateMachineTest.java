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
                            || kind == RestrictionKind.GUARDIAN_REVOKED))
                            || (state == RestrictionState.ACTIVE
                            && event == RestrictionEvent.EXPIRE
                            && kind == RestrictionKind.TEMPORARY)
                            || (state == RestrictionState.EXPIRED
                            && event == RestrictionEvent.REVOKE
                            && kind == RestrictionKind.TEMPORARY);
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
                                        : event == RestrictionEvent.EXPIRE
                                        ? RestrictionState.EXPIRED
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
        // 合法迁移：PENDING+ACTIVATE×4 + PENDING+REJECT×4 + ACTIVE+REVOKE×2
        // + ACTIVE+EXPIRE×1 + EXPIRED+REVOKE×1 = 12
        assertEquals(12, legalCount);
    }

    @Test
    void onlyTemporaryRestrictionsCanExpireAndStillBeRevokedAfterExpiry() {
        for (RestrictionKind kind : List.of(RestrictionKind.FINAL_VIOLATION,
                RestrictionKind.CONTRACT_TERMINATED, RestrictionKind.GUARDIAN_REVOKED)) {
            assertEquals(ErrorCode.B2001, RestrictionStateMachine.transit(
                    RestrictionState.ACTIVE, RestrictionEvent.EXPIRE, kind).code(), kind.name());
        }
        TransitionResult expired = RestrictionStateMachine.transit(
                RestrictionState.ACTIVE, RestrictionEvent.EXPIRE, RestrictionKind.TEMPORARY);
        assertEquals(RestrictionState.EXPIRED, expired.targetState());
        assertEquals("RESTRICTION_EXPIRED", expired.ruleId());

        TransitionResult revoked = RestrictionStateMachine.transit(
                RestrictionState.EXPIRED, RestrictionEvent.REVOKE, RestrictionKind.TEMPORARY);
        assertEquals(RestrictionState.REVOKED, revoked.targetState());
        assertEquals(ErrorCode.B2001, RestrictionStateMachine.transit(
                RestrictionState.EXPIRED, RestrictionEvent.REVOKE,
                RestrictionKind.GUARDIAN_REVOKED).code());
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
