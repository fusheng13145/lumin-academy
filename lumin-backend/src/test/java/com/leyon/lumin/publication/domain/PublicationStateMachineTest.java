package com.leyon.lumin.publication.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.BusinessState;
import com.leyon.lumin.foundation.values.DraftState;
import com.leyon.lumin.foundation.values.PublicationEvent;
import com.leyon.lumin.foundation.values.TargetType;
import com.leyon.lumin.foundation.values.VersionState;
import com.leyon.lumin.foundation.values.WorkflowState;
import com.leyon.lumin.support.MatrixFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T15（07 §2.2）：03 §2.1/2.2/2.3 每状态 × 每事件全组合、未列迁移 B2001、
 * 终态不可回退、章节禁 COMPLETED、前提不满足不提交、重放返回原结果。
 *
 * <p>测试内独立列出 03 原文的合法迁移键作为 oracle，不复用被测迁移表判定。
 */
class PublicationStateMachineTest {

    private static TransitionContext satisfied() {
        return new TransitionContext(TargetType.WORK,
                true, true, true, "作者申请恢复连载",
                true, true, true, true, true, true, true, true, true,
                false, true, false);
    }

    private static String key(String family, Object state, PublicationEvent event) {
        return family + "|" + ((Enum<?>) state).name() + "|" + event.name();
    }

    /** 03 §2.1–§2.3 明列的合法迁移（独立 oracle，共 25 条）。 */
    private static Set<String> legalKeys() {
        Set<String> keys = new HashSet<>();
        keys.add(key("BUSINESS", BusinessState.DRAFT, PublicationEvent.FIRST_VERSION_PUBLISHED));
        keys.add(key("BUSINESS", BusinessState.ACTIVE, PublicationEvent.COMPLETE_REQUESTED));
        keys.add(key("BUSINESS", BusinessState.COMPLETED, PublicationEvent.RESUME_SERIAL));
        for (BusinessState state : new BusinessState[]{BusinessState.DRAFT, BusinessState.ACTIVE,
                BusinessState.COMPLETED}) {
            keys.add(key("BUSINESS", state, PublicationEvent.TAKEDOWN_EFFECTIVE));
        }
        keys.add(key("BUSINESS", BusinessState.REMOVED, PublicationEvent.TEMPORARY_APPEAL_UPHELD));
        for (BusinessState state : new BusinessState[]{BusinessState.DRAFT, BusinessState.ACTIVE,
                BusinessState.COMPLETED, BusinessState.REMOVED}) {
            keys.add(key("BUSINESS", state, PublicationEvent.CONTRACT_TERMINATED));
        }
        keys.add(key("DRAFT", DraftState.EDITABLE, PublicationEvent.SAVE));
        keys.add(key("DRAFT", DraftState.EDITABLE, PublicationEvent.SUBMIT));
        keys.add(key("DRAFT", DraftState.EDITABLE, PublicationEvent.DISCARD));
        keys.add(key("WORKFLOW", WorkflowState.SUBMITTED, PublicationEvent.AUTO_TASK_CLAIMED));
        for (PublicationEvent event : new PublicationEvent[]{PublicationEvent.AUTO_PASS,
                PublicationEvent.AUTO_REVISE, PublicationEvent.ROUTE_HUMAN_REVIEW,
                PublicationEvent.SUSPEND}) {
            keys.add(key("WORKFLOW", WorkflowState.AUTO_REVIEW, event));
        }
        keys.add(key("WORKFLOW", WorkflowState.SUSPENDED, PublicationEvent.RETRY_TO_HUMAN));
        keys.add(key("WORKFLOW", WorkflowState.HUMAN_REVIEW, PublicationEvent.HUMAN_FINAL_PASS));
        keys.add(key("WORKFLOW", WorkflowState.HUMAN_REVIEW, PublicationEvent.HUMAN_FINAL_REJECT));
        for (WorkflowState state : new WorkflowState[]{WorkflowState.SUBMITTED,
                WorkflowState.AUTO_REVIEW, WorkflowState.HUMAN_REVIEW, WorkflowState.SUSPENDED}) {
            keys.add(key("WORKFLOW", state, PublicationEvent.WITHDRAW));
        }
        return keys;
    }

    private static TransitionResult invoke(String family, Enum<?> state,
                                           PublicationEvent event, TransitionContext ctx) {
        return switch (family) {
            case "BUSINESS" -> PublicationStateMachine.business(
                    (BusinessState) state, event, ctx);
            case "DRAFT" -> PublicationStateMachine.draft((DraftState) state, event, ctx);
            case "WORKFLOW" -> PublicationStateMachine.workflow(
                    (WorkflowState) state, event, ctx);
            default -> throw new IllegalArgumentException("未知状态机族：" + family);
        };
    }

    /** 15 态 × 19 事件 = 285 组合：合法唯一、非法一律 B2001。 */
    @ParameterizedTest
    @MethodSource("com.leyon.lumin.support.MatrixFactory#stateEventMatrix")
    void allStateEventPairsFollowTransitionTable(String family, Object state,
                                                 PublicationEvent event) {
        TransitionResult result = invoke(family, (Enum<?>) state, event, satisfied());
        boolean legal = legalKeys().contains(key(family, state, event));
        assertEquals(legal, result.accepted(),
                "合法迁移唯一：" + family + "/" + state + "/" + event);
        assertEquals(state, result.fromState());
        if (legal) {
            assertEquals(ErrorCode.OK, result.code());
        } else {
            assertEquals(ErrorCode.B2001, result.code(), "未列迁移一律 B2001");
            assertTrue(result.auditRequired(), "非法迁移必须审计");
            assertEquals(state, result.toState(), "拒绝时状态不变");
            assertTrue(result.isReject());
        }
    }

    @Test
    void matrixSizesMatchSpec() {
        assertEquals(285, MatrixFactory.stateEventMatrixSize());
        assertEquals(19, PublicationStateMachine.allEvents().size());
        assertTrue(PublicationStateMachine.allEvents().contains(PublicationEvent.UNDEFINED));
    }

    /** 终态不可回退：TERMINATED 与三个流程终态、两个草稿冻结态。 */
    @Test
    void terminalStatesNeverTransition() {
        for (PublicationEvent event : PublicationEvent.values()) {
            TransitionResult terminated = PublicationStateMachine.business(
                    BusinessState.TERMINATED, event, satisfied());
            assertFalse(terminated.accepted(), "TERMINATED 不可恢复传播：" + event);
            assertEquals(ErrorCode.B2001, terminated.code());

            for (WorkflowState state : new WorkflowState[]{WorkflowState.APPROVED,
                    WorkflowState.REJECTED, WorkflowState.CANCELLED}) {
                TransitionResult workflow = PublicationStateMachine.workflow(state, event, satisfied());
                assertFalse(workflow.accepted(), state + " 为终态：" + event);
            }
            for (DraftState state : new DraftState[]{DraftState.SUBMITTED, DraftState.DISCARDED}) {
                TransitionResult draft = PublicationStateMachine.draft(state, event, satisfied());
                assertFalse(draft.accepted(), state + " 冻结：" + event);
            }
        }
    }

    @Test
    void chapterCannotCompleteAndRequiresApprovedParent() {
        TransitionContext chapter = new TransitionContext(TargetType.CHAPTER,
                true, true, true, "完结申请",
                true, true, true, true, true, true, true, true, true,
                false, true, false);
        TransitionResult complete = PublicationStateMachine.business(
                BusinessState.ACTIVE, PublicationEvent.COMPLETE_REQUESTED, chapter);
        assertFalse(complete.accepted(), "章节禁止 COMPLETED");
        assertEquals(ErrorCode.B2001, complete.code());

        TransitionContext noParent = new TransitionContext(TargetType.WORK,
                true, true, true, "最终裁决",
                true, true, true, true, true, true, true, true, false,
                false, true, false);
        TransitionResult pass = PublicationStateMachine.workflow(
                WorkflowState.HUMAN_REVIEW, PublicationEvent.HUMAN_FINAL_PASS, noParent);
        assertFalse(pass.accepted(), "父作品无可见已通过版本时不得提交通过裁决");
        assertEquals(ErrorCode.B2001, pass.code());
    }

    @Test
    void unsatisfiedPrerequisiteRejectsInsteadOfSilentlyPassing() {
        TransitionContext staleLock = new TransitionContext(TargetType.WORK,
                true, true, true, "提交",
                true, true, true, true, true, true, true, true, true,
                false, false, false);
        TransitionResult submit = PublicationStateMachine.draft(
                DraftState.EDITABLE, PublicationEvent.SUBMIT, staleLock);
        assertFalse(submit.accepted());
        assertEquals(ErrorCode.B2001, submit.code());
        assertTrue(submit.auditRequired());
    }

    @Test
    void workflowTransitionsCarryVersionDoubleTarget() {
        TransitionResult pass = PublicationStateMachine.workflow(
                WorkflowState.AUTO_REVIEW, PublicationEvent.AUTO_PASS, satisfied());
        assertTrue(pass.accepted());
        assertEquals(WorkflowState.APPROVED, pass.toState());
        assertEquals(VersionState.APPROVED, pass.versionState());
        assertTrue(pass.terminal());

        TransitionResult route = PublicationStateMachine.workflow(
                WorkflowState.AUTO_REVIEW, PublicationEvent.ROUTE_HUMAN_REVIEW, satisfied());
        assertEquals(WorkflowState.HUMAN_REVIEW, route.toState());
        assertEquals(VersionState.SUBMITTED, route.versionState());
        assertFalse(route.terminal());
    }

    @Test
    void suspendedResumeAlwaysGoesToHumanReview() {
        TransitionResult result = PublicationStateMachine.workflow(
                WorkflowState.SUSPENDED, PublicationEvent.RETRY_TO_HUMAN, satisfied());
        assertTrue(result.accepted());
        assertEquals(WorkflowState.HUMAN_REVIEW, result.toState());
        assertEquals(VersionState.SUBMITTED, result.versionState());
    }

    @Test
    void replayReturnsOriginalResultWithoutReExecuting() {
        TransitionResult original = PublicationStateMachine.workflow(
                WorkflowState.HUMAN_REVIEW, PublicationEvent.HUMAN_FINAL_PASS, satisfied());
        assertSame(original, PublicationStateMachine.replay(original));
        TransitionResult rejected = PublicationStateMachine.business(
                BusinessState.TERMINATED, PublicationEvent.RESUME_SERIAL, satisfied());
        assertSame(rejected, PublicationStateMachine.replay(rejected));
    }

    @Test
    void restorationUsesRecordedPriorStateSignal() {
        TransitionResult restore = PublicationStateMachine.business(
                BusinessState.REMOVED, PublicationEvent.TEMPORARY_APPEAL_UPHELD, satisfied());
        assertTrue(restore.accepted());
        assertEquals(null, restore.toState(),
                "纯映射不臆造前态；由调用方从保存的前态记录恢复");
        assertNotNull(restore.ruleId());
    }

    /** 负向注入样本：UNDEFINED 事件与终态迁移全部失败。 */
    @ParameterizedTest
    @MethodSource("com.leyon.lumin.support.MatrixFactory#illegalTransitions")
    void injectedIllegalTransitionsAreRejected(String family, Object state,
                                               PublicationEvent event) {
        TransitionResult result = invoke(family, (Enum<?>) state, event, satisfied());
        assertFalse(result.accepted(), "注入样本必须被拒绝：" + family + "/" + state + "/" + event);
        assertEquals(ErrorCode.B2001, result.code());
        assertTrue(result.auditRequired());
    }

    /** 补充：迁移表辅助方法、record 相等性与状态机空值防护。 */
    @Test
    void transitionTableHelpersAndNullGuardsAreExplicit() {
        assertEquals(null, TransitionTable.lookup(null, PublicationEvent.SAVE));
        assertEquals(null, TransitionTable.lookup(BusinessState.DRAFT, null));
        assertEquals(285, TransitionTable.allStateEventPairs().size());
        assertEquals(19, TransitionTable.allEvents().size());
        assertEquals(legalKeys().size(), TransitionTable.RULES.size(),
                "静态迁移表必须与 03 原文合法键集合一一对应");
        assertFalse(PublicationStateMachine.business(
                null, PublicationEvent.SAVE, satisfied()).accepted());
        assertFalse(PublicationStateMachine.draft(
                DraftState.EDITABLE, null, satisfied()).accepted());
        assertFalse(PublicationStateMachine.workflow(
                WorkflowState.SUBMITTED, PublicationEvent.WITHDRAW, null).accepted());
        assertEquals(null, PublicationStateMachine.replay(null));
        assertFalse(PublicationStateMachine.draft(
                DraftState.EDITABLE, PublicationEvent.SAVE, satisfied()).isReject());

        TransitionTable.TransitionKey first =
                new TransitionTable.TransitionKey(BusinessState.DRAFT, PublicationEvent.SAVE);
        TransitionTable.TransitionKey same =
                new TransitionTable.TransitionKey(BusinessState.DRAFT, PublicationEvent.SAVE);
        TransitionTable.TransitionKey different =
                new TransitionTable.TransitionKey(BusinessState.ACTIVE, PublicationEvent.SAVE);
        assertEquals(first, same);
        assertFalse(first.equals(different));
        assertFalse(first.equals(null));
        assertFalse(first.equals("not-a-key"));
        assertEquals(first.hashCode(), same.hashCode());
        assertNotNull(first.state());
        assertNotNull(TransitionTable.lookup(DraftState.EDITABLE, PublicationEvent.SAVE));
    }
}
