package com.leyon.lumin.publication.domain;

import com.leyon.lumin.foundation.values.BusinessState;
import com.leyon.lumin.foundation.values.DraftState;
import com.leyon.lumin.foundation.values.PublicationEvent;
import com.leyon.lumin.foundation.values.TargetType;
import com.leyon.lumin.foundation.values.VersionState;
import com.leyon.lumin.foundation.values.WorkflowState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 03 §2.1 / §2.2 / §2.3 的合法迁移静态表。
 *
 * <p>只做 {@code (state, event, ctx) → (targetState, versionState)} 纯映射：
 * 不含机审阈值、风险分级、自动放行路由，不产生公开指针（N-02 / K-03）。
 * 未列出的组合由 PublicationStateMachine 统一拒绝为 B2001。
 */
public final class TransitionTable {

    /** 迁移键：源状态 + 事件。 */
    public record TransitionKey(Enum<?> state, PublicationEvent event) {
    }

    /** 迁移规则：目标态、版本双目标、终态、规则标识与全部前提。 */
    public record TransitionRule(
            Enum<?> toState,
            VersionState versionState,
            boolean terminal,
            String ruleId,
            List<Predicate<TransitionContext>> prerequisites) {
    }

    /** 穷举源：状态机族 + 源状态 + 事件。 */
    public record StateEventPair(String family, Enum<?> state, PublicationEvent event) {
    }

    private static final Predicate<TransitionContext> OWNER = TransitionContext::authorIsOwner;
    private static final Predicate<TransitionContext> NO_PENDING = TransitionContext::noPendingWorkflow;
    private static final Predicate<TransitionContext> ALL_CHAPTERS =
            TransitionContext::allExistingChaptersPublished;
    private static final Predicate<TransitionContext> LEGAL =
            TransitionContext::legalConfirmationsPresent;
    private static final Predicate<TransitionContext> PRIOR_STATE =
            TransitionContext::priorStateRecorded;
    private static final Predicate<TransitionContext> RESTRICTIONS_LIFTED =
            TransitionContext::allRestrictionsLifted;
    private static final Predicate<TransitionContext> COPYRIGHT = TransitionContext::copyrightValid;
    private static final Predicate<TransitionContext> ACCOUNT = TransitionContext::accountAllows;
    private static final Predicate<TransitionContext> POINTER =
            TransitionContext::pointerRechecked;
    private static final Predicate<TransitionContext> MACHINE_COMPLETE =
            TransitionContext::machineReviewComplete;
    private static final Predicate<TransitionContext> NO_DECISION =
            TransitionContext::noEffectiveDecision;
    private static final Predicate<TransitionContext> PARENT_APPROVED =
            TransitionContext::parentWorkHasVisibleApprovedVersion;
    private static final Predicate<TransitionContext> LOCK_MATCH =
            TransitionContext::lockVersionMatches;
    private static final Predicate<TransitionContext> WORK_ONLY =
            ctx -> ctx.targetType() == TargetType.WORK;
    private static final Predicate<TransitionContext> REASON_PRESENT =
            ctx -> ctx.reason() != null && !ctx.reason().isBlank();
    private static final Predicate<TransitionContext> NOT_GOVERNANCE_RESTORE =
            ctx -> !ctx.isGovernanceRestoration();

    /** 静态只读迁移表；全部前提由调用方注入的 TransitionContext 提供。 */
    public static final Map<TransitionKey, TransitionRule> RULES = buildRules();

    private TransitionTable() {
    }

    private static Map<TransitionKey, TransitionRule> buildRules() {
        Map<TransitionKey, TransitionRule> rules = new HashMap<>();

        // ---- 03 §2.1 作品与章节业务态（章节禁 COMPLETED；TERMINATED 不可恢复传播）----
        put(rules, BusinessState.DRAFT, PublicationEvent.FIRST_VERSION_PUBLISHED,
                BusinessState.ACTIVE, null, "BUSINESS_FIRST_VERSION", POINTER, PARENT_APPROVED);
        put(rules, BusinessState.ACTIVE, PublicationEvent.COMPLETE_REQUESTED,
                BusinessState.COMPLETED, null, "BUSINESS_COMPLETE",
                OWNER, NO_PENDING, ALL_CHAPTERS, WORK_ONLY);
        put(rules, BusinessState.COMPLETED, PublicationEvent.RESUME_SERIAL,
                BusinessState.ACTIVE, null, "BUSINESS_RESUME",
                OWNER, REASON_PRESENT, NOT_GOVERNANCE_RESTORE);
        for (BusinessState state : List.of(BusinessState.DRAFT, BusinessState.ACTIVE,
                BusinessState.COMPLETED)) {
            put(rules, state, PublicationEvent.TAKEDOWN_EFFECTIVE,
                    BusinessState.REMOVED, null, "BUSINESS_TAKEDOWN", PRIOR_STATE);
        }
        // 记录的前态由调用方从保存的前态记录恢复；纯映射以 null 表达，不臆造具体前态
        put(rules, BusinessState.REMOVED, PublicationEvent.TEMPORARY_APPEAL_UPHELD,
                null, null, "BUSINESS_RESTORE_PRIOR_RECORDED_STATE",
                RESTRICTIONS_LIFTED, COPYRIGHT, ACCOUNT, POINTER);
        for (BusinessState state : List.of(BusinessState.DRAFT, BusinessState.ACTIVE,
                BusinessState.COMPLETED, BusinessState.REMOVED)) {
            put(rules, state, PublicationEvent.CONTRACT_TERMINATED,
                    BusinessState.TERMINATED, null, "BUSINESS_CONTRACT_TERMINATED", LEGAL);
        }

        // ---- 03 §2.2 草稿态 3 态（SUBMITTED 冻结 / DISCARDED 终态）----
        put(rules, DraftState.EDITABLE, PublicationEvent.SAVE,
                DraftState.EDITABLE, null, "DRAFT_SAVE", LOCK_MATCH);
        put(rules, DraftState.EDITABLE, PublicationEvent.SUBMIT,
                DraftState.SUBMITTED, null, "DRAFT_SUBMIT",
                OWNER, LEGAL, NO_PENDING, LOCK_MATCH);
        put(rules, DraftState.EDITABLE, PublicationEvent.DISCARD,
                DraftState.DISCARDED, null, "DRAFT_DISCARD", OWNER);

        // ---- 03 §2.3 版本态 4 + 流程态 7 ----
        put(rules, WorkflowState.SUBMITTED, PublicationEvent.AUTO_TASK_CLAIMED,
                WorkflowState.AUTO_REVIEW, VersionState.SUBMITTED, "WF_AUTO_CLAIM",
                MACHINE_COMPLETE);
        put(rules, WorkflowState.AUTO_REVIEW, PublicationEvent.AUTO_PASS,
                WorkflowState.APPROVED, VersionState.APPROVED, "WF_AUTO_PASS",
                MACHINE_COMPLETE, ACCOUNT);
        put(rules, WorkflowState.AUTO_REVIEW, PublicationEvent.AUTO_REVISE,
                WorkflowState.REJECTED, VersionState.REJECTED, "WF_AUTO_REVISE",
                MACHINE_COMPLETE);
        put(rules, WorkflowState.AUTO_REVIEW, PublicationEvent.ROUTE_HUMAN_REVIEW,
                WorkflowState.HUMAN_REVIEW, VersionState.SUBMITTED, "WF_ROUTE_HUMAN");
        put(rules, WorkflowState.AUTO_REVIEW, PublicationEvent.SUSPEND,
                WorkflowState.SUSPENDED, VersionState.SUBMITTED, "WF_SUSPEND");
        put(rules, WorkflowState.SUSPENDED, PublicationEvent.RETRY_TO_HUMAN,
                WorkflowState.HUMAN_REVIEW, VersionState.SUBMITTED, "WF_RETRY_TO_HUMAN",
                MACHINE_COMPLETE);
        put(rules, WorkflowState.HUMAN_REVIEW, PublicationEvent.HUMAN_FINAL_PASS,
                WorkflowState.APPROVED, VersionState.APPROVED, "WF_HUMAN_PASS",
                MACHINE_COMPLETE, NO_DECISION, PARENT_APPROVED);
        put(rules, WorkflowState.HUMAN_REVIEW, PublicationEvent.HUMAN_FINAL_REJECT,
                WorkflowState.REJECTED, VersionState.REJECTED, "WF_HUMAN_REJECT",
                NO_DECISION);
        for (WorkflowState state : List.of(WorkflowState.SUBMITTED,
                WorkflowState.AUTO_REVIEW, WorkflowState.HUMAN_REVIEW, WorkflowState.SUSPENDED)) {
            put(rules, state, PublicationEvent.WITHDRAW,
                    WorkflowState.CANCELLED, VersionState.WITHDRAWN, "WF_WITHDRAW",
                    OWNER, NO_DECISION);
        }
        return Collections.unmodifiableMap(rules);
    }

    @SafeVarargs
    private static void put(Map<TransitionKey, TransitionRule> rules, Enum<?> from,
                            PublicationEvent event, Enum<?> to, VersionState versionState,
                            String ruleId, Predicate<TransitionContext>... prerequisites) {
        rules.put(new TransitionKey(from, event), new TransitionRule(
                to, versionState, terminalOf(to), ruleId, List.of(prerequisites)));
    }

    private static boolean terminalOf(Enum<?> toState) {
        if (toState instanceof BusinessState business) {
            return business.isTerminal();
        }
        if (toState instanceof DraftState draft) {
            return draft.isTerminal();
        }
        if (toState instanceof WorkflowState workflow) {
            return workflow.isTerminal();
        }
        return false;
    }

    /** 查表；未命中返回 null，由状态机统一拒绝为 B2001。 */
    public static TransitionRule lookup(Enum<?> state, PublicationEvent event) {
        if (state == null || event == null) {
            return null;
        }
        return RULES.get(new TransitionKey(state, event));
    }

    /** 三张状态机每状态 × 每事件全组合（含 UNDEFINED），供 T15 穷举。 */
    public static List<StateEventPair> allStateEventPairs() {
        List<StateEventPair> pairs = new ArrayList<>();
        for (BusinessState state : BusinessState.values()) {
            for (PublicationEvent event : PublicationEvent.values()) {
                pairs.add(new StateEventPair("BUSINESS", state, event));
            }
        }
        for (DraftState state : DraftState.values()) {
            for (PublicationEvent event : PublicationEvent.values()) {
                pairs.add(new StateEventPair("DRAFT", state, event));
            }
        }
        for (WorkflowState state : WorkflowState.values()) {
            for (PublicationEvent event : PublicationEvent.values()) {
                pairs.add(new StateEventPair("WORKFLOW", state, event));
            }
        }
        return List.copyOf(pairs);
    }

    public static Set<PublicationEvent> allEvents() {
        return Collections.unmodifiableSet(EnumSet.allOf(PublicationEvent.class));
    }
}
