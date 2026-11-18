package com.leyon.lumin.configuration.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.ConfigRevisionEvent;
import com.leyon.lumin.foundation.values.ConfigRevisionState;
import com.leyon.lumin.configuration.domain.ConfigRevisionStateMachine.TransitionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置修订状态机全组合穷举（03 §2.4 config_revision；T30–T32 状态面）。 */
class ConfigRevisionStateMachineTest {

    @Test
    void fullCombinationMatrixMatchesSpec() {
        int legalCount = 0;
        for (ConfigRevisionState state : List.of(ConfigRevisionState.values())) {
            for (ConfigRevisionEvent event : List.of(ConfigRevisionEvent.values())) {
                TransitionResult result = ConfigRevisionStateMachine.transit(state, event);
                boolean legal = (state == ConfigRevisionState.DRAFT
                        && event == ConfigRevisionEvent.SUBMIT_FOR_REVIEW)
                        || (state == ConfigRevisionState.IN_REVIEW
                        && (event == ConfigRevisionEvent.APPROVE
                        || event == ConfigRevisionEvent.REJECT))
                        || (state == ConfigRevisionState.APPROVED
                        && (event == ConfigRevisionEvent.ACTIVATE
                        || event == ConfigRevisionEvent.CANCEL))
                        || (state == ConfigRevisionState.ACTIVE
                        && event == ConfigRevisionEvent.SUPERSEDE);
                assertEquals(legal, result.accepted(), state + "+" + event);
                assertTrue(result.auditRequired());
                if (legal) {
                    legalCount++;
                    assertEquals(ErrorCode.OK, result.code());
                } else {
                    assertEquals(ErrorCode.B2001, result.code(), state + "+" + event);
                    assertEquals(state, result.targetState());
                }
            }
        }
        assertEquals(6, legalCount);
    }

    @Test
    void onlyActiveParticipatesAndSupersededIsTerminal() {
        // 回滚创建新修订、不修改历史：ACTIVE 只能被取代（服务层写新修订并触发 SUPERSEDE）
        assertEquals(ConfigRevisionState.SUPERSEDED, ConfigRevisionStateMachine.transit(
                ConfigRevisionState.ACTIVE, ConfigRevisionEvent.SUPERSEDE).targetState());
        for (ConfigRevisionEvent event : List.of(ConfigRevisionEvent.values())) {
            for (ConfigRevisionState terminal : List.of(ConfigRevisionState.REJECTED,
                    ConfigRevisionState.CANCELLED, ConfigRevisionState.SUPERSEDED)) {
                assertEquals(ErrorCode.B2001,
                        ConfigRevisionStateMachine.transit(terminal, event).code(),
                        terminal + "+" + event);
            }
        }
    }

    @Test
    void approvedCannotGoBackToInReview() {
        // 批后不得替换 payload；需修订则新建提案（05 §4.2）
        assertEquals(ErrorCode.B2001, ConfigRevisionStateMachine.transit(
                ConfigRevisionState.APPROVED, ConfigRevisionEvent.SUBMIT_FOR_REVIEW).code());
        assertEquals(ErrorCode.B2001, ConfigRevisionStateMachine.transit(
                ConfigRevisionState.APPROVED, ConfigRevisionEvent.APPROVE).code());
    }

    @Test
    void nullsAreRejected() {
        assertEquals(ErrorCode.B2001, ConfigRevisionStateMachine.transit(
                null, ConfigRevisionEvent.APPROVE).code());
        assertEquals(ErrorCode.B2001, ConfigRevisionStateMachine.transit(
                ConfigRevisionState.DRAFT, null).code());
    }
}
