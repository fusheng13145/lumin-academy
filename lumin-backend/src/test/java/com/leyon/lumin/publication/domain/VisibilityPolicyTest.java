package com.leyon.lumin.publication.domain;

import com.leyon.lumin.foundation.values.AccountStatus;
import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.values.BusinessState;
import com.leyon.lumin.foundation.values.Grade;
import com.leyon.lumin.foundation.values.RestrictionKind;
import com.leyon.lumin.foundation.values.TargetType;
import com.leyon.lumin.foundation.values.VersionState;
import com.leyon.lumin.foundation.values.VisibilityReason;
import com.leyon.lumin.foundation.values.WorkflowState;
import com.leyon.lumin.support.MatrixFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T13–T15 前半（07 §2.2）：≥2800 组合穷举、v1/v2、全局下架、
 * 引用错配、八步顺序与 allowed=false → versionId=null 不变量。
 *
 * <p>测试内独立实现内容等级 oracle，不复用被测实现作为判定依据。
 */
class VisibilityPolicyTest {

    private static final String TARGET_ID = "W";
    private static final String V1 = "V1";
    private static final long POLICY_REVISION = 7L;

    /** 基线：V1 已通过且被公开指针引用，全部引用一致、无治理限制。 */
    private static VisibilityInput baseline() {
        return input(BusinessState.ACTIVE, VersionState.APPROVED, WorkflowState.APPROVED,
                AgeBand.C3, Grade.G);
    }

    private static VisibilityInput input(BusinessState business, VersionState version,
                                         WorkflowState workflow, AgeBand age, Grade grade) {
        return new VisibilityInput(
                age, AccountStatus.ACTIVE, TargetType.WORK, TARGET_ID,
                business, business, Set.of(),
                V1, version, grade,
                "WF-1", workflow, V1,
                POLICY_REVISION, POLICY_REVISION,
                V1, TARGET_ID, TARGET_ID,
                true, true, true, false, false);
    }

    private static VisibilityInput copy(VisibilityInput base, String pointerTargetId,
                                        String versionTargetId, String workflowBoundVersionId,
                                        String candidateVersionId, long boundPolicyRevision,
                                        boolean shaMatches, boolean authoritativeOk,
                                        boolean everPublished, String pointerVersionId,
                                        String boundWorkflowId, Set<RestrictionKind> restrictions,
                                        BusinessState targetState, BusinessState parentState,
                                        AccountStatus accountStatus, AgeBand age, Grade grade,
                                        VersionState versionState, WorkflowState workflowState,
                                        boolean adultEnabled, boolean adultConfirmed) {
        return new VisibilityInput(age, accountStatus, TargetType.WORK, TARGET_ID,
                targetState, parentState, restrictions,
                candidateVersionId, versionState, grade,
                boundWorkflowId, workflowState, workflowBoundVersionId,
                boundPolicyRevision, POLICY_REVISION,
                pointerVersionId, pointerTargetId, versionTargetId,
                shaMatches, authoritativeOk, everPublished, adultEnabled, adultConfirmed);
    }

    private static boolean gradeReadable(AgeBand band, Grade grade) {
        if (band == null || grade == null) {
            return false;
        }
        AgeBand reading = band == AgeBand.C4 ? AgeBand.C1 : band;
        if (grade == Grade.A) {
            return false; // P0 契约 AccessPolicy.adultContentEnabled 恒 [false]
        }
        if (grade == Grade.PG_16) {
            return reading == AgeBand.C2 || reading == AgeBand.C3;
        }
        return true;
    }

    /** 5 × 4 × 7 × 5 × 4 = 2800 组合：逐组校验全部不变量。 */
    @ParameterizedTest
    @MethodSource("com.leyon.lumin.support.MatrixFactory#visibilityMatrix")
    void visibilityMatrixInvariants(BusinessState business, VersionState version,
                                    WorkflowState workflow, AgeBand age, Grade grade) {
        VisibilityDecision decision = VisibilityPolicy.decide(
                input(business, version, workflow, age, grade));
        assertNotNull(decision.reason(), "必须返回五类理由之一");
        if (decision.allowed()) {
            assertEquals(VisibilityReason.ALLOW, decision.reason());
            assertEquals(V1, decision.versionId());
            assertFalse(decision.raiseIncident());
        } else {
            assertNull(decision.versionId(), "allowed=false 时 versionId 必须为空");
        }
        assertEquals(decision.reason() == VisibilityReason.INCONSISTENT,
                decision.raiseIncident(), "只有 INCONSISTENT 触发布尔告警信号");

        boolean legal = (business == BusinessState.ACTIVE || business == BusinessState.COMPLETED)
                && version == VersionState.APPROVED
                && workflow == WorkflowState.APPROVED
                && gradeReadable(age, grade);
        assertEquals(legal, decision.allowed(),
                "只有明确合法组合可见：" + business + "/" + version + "/" + workflow + "/" + age + "/" + grade);
    }

    @Test
    void matrixSizeIsAtLeast2800() {
        assertEquals(2800, MatrixFactory.visibilityMatrixSize());
    }

    /** V1 通过、夹具另有 V2 待审时，输入只携带公开指针所指的 V1，读者仍读 V1。 */
    @Test
    void approvedV1RemainsVisibleWhileV2Pending() {
        VisibilityDecision decision = VisibilityPolicy.decide(baseline());
        assertTrue(decision.allowed());
        assertEquals(V1, decision.versionId());
        assertEquals(VisibilityReason.ALLOW, decision.reason());
        assertFalse(decision.raiseIncident());
    }

    /** 全局治理阻断同时隐藏 v1 与 v2（03 INV-03）。 */
    @Test
    void globalRestrictionHidesAllVersions() {
        VisibilityInput blockedV1 = copy(baseline(), TARGET_ID, TARGET_ID, V1, V1,
                POLICY_REVISION, true, true, true, V1, "WF-1",
                Set.of(RestrictionKind.TEMPORARY), BusinessState.ACTIVE, BusinessState.ACTIVE,
                AccountStatus.ACTIVE, AgeBand.C3, Grade.G,
                VersionState.APPROVED, WorkflowState.APPROVED, false, false);
        VisibilityDecision v1 = VisibilityPolicy.decide(blockedV1);
        assertFalse(v1.allowed());
        assertEquals(VisibilityReason.BLOCKED, v1.reason());
        assertNull(v1.versionId());
        assertFalse(v1.raiseIncident());

        VisibilityInput blockedV2 = copy(baseline(), TARGET_ID, TARGET_ID, "V2", "V2",
                POLICY_REVISION, true, true, true, "V2", "WF-2",
                Set.of(RestrictionKind.FINAL_VIOLATION), BusinessState.ACTIVE, BusinessState.ACTIVE,
                AccountStatus.ACTIVE, AgeBand.C3, Grade.G,
                VersionState.SUBMITTED, WorkflowState.HUMAN_REVIEW, false, false);
        VisibilityDecision v2 = VisibilityPolicy.decide(blockedV2);
        assertFalse(v2.allowed());
        assertEquals(VisibilityReason.BLOCKED, v2.reason());
    }

    @Test
    void referenceMismatchRaisesIncidentWithoutExposingVersion() {
        // 指针错目标
        VisibilityDecision wrongTarget = VisibilityPolicy.decide(
                copy(baseline(), "OTHER", TARGET_ID, V1, V1, POLICY_REVISION,
                        true, true, true, V1, "WF-1", Set.of(),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.INCONSISTENT, wrongTarget.reason());
        assertTrue(wrongTarget.raiseIncident());
        assertNull(wrongTarget.versionId());

        // 流程绑定版本错配
        VisibilityDecision wrongWorkflow = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, "V2", V1, POLICY_REVISION,
                        true, true, true, V1, "WF-1", Set.of(),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.INCONSISTENT, wrongWorkflow.reason());
        assertTrue(wrongWorkflow.raiseIncident());
    }

    @Test
    void authoritativeReadFailureAndShaMismatchAreInconsistent() {
        VisibilityDecision readFailed = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, V1, POLICY_REVISION,
                        true, false, true, V1, "WF-1", Set.of(),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.INCONSISTENT, readFailed.reason());
        assertTrue(readFailed.raiseIncident());

        VisibilityDecision shaFailed = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, V1, POLICY_REVISION,
                        false, true, true, V1, "WF-1", Set.of(),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.INCONSISTENT, shaFailed.reason());

        VisibilityDecision missingWorkflow = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, V1, POLICY_REVISION,
                        true, true, true, V1, "", Set.of(),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.INCONSISTENT, missingWorkflow.reason());
    }

    @Test
    void policyRevisionMismatchAndDraftWithPointerAreInconsistent() {
        VisibilityDecision revisionMismatch = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, V1, POLICY_REVISION - 1,
                        true, true, true, V1, "WF-1", Set.of(),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.INCONSISTENT, revisionMismatch.reason());
        assertTrue(revisionMismatch.raiseIncident());

        VisibilityDecision draftWithPointer = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, V1, POLICY_REVISION,
                        true, true, true, V1, "WF-1", Set.of(),
                        BusinessState.DRAFT, BusinessState.DRAFT, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.INCONSISTENT, draftWithPointer.reason());
        assertTrue(draftWithPointer.raiseIncident());
    }

    @Test
    void neverPublishedHasNoPointerReturnsNotPublishedWithoutIncident() {
        VisibilityDecision decision = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, V1, POLICY_REVISION,
                        true, true, false, null, "WF-1", Set.of(),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertFalse(decision.allowed());
        assertEquals(VisibilityReason.NOT_PUBLISHED, decision.reason());
        assertFalse(decision.raiseIncident());
        assertNull(decision.versionId());
    }

    @Test
    void everPublishedWithoutPointerIsInconsistentAndRaisesIncident() {
        VisibilityDecision decision = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, V1, POLICY_REVISION,
                        true, true, true, null, "WF-1", Set.of(),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.INCONSISTENT, decision.reason());
        assertTrue(decision.raiseIncident());
    }

    @Test
    void ageRestrictionAndClosedAdultGradeDoNotRaiseIncident() {
        VisibilityDecision ageLimited = VisibilityPolicy.decide(
                input(BusinessState.ACTIVE, VersionState.APPROVED, WorkflowState.APPROVED,
                        AgeBand.C0, Grade.PG_16));
        assertEquals(VisibilityReason.AGE_RESTRICTED, ageLimited.reason());
        assertFalse(ageLimited.raiseIncident());

        VisibilityDecision adultClosed = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, V1, POLICY_REVISION,
                        true, true, true, V1, "WF-1", Set.of(),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.A, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.AGE_RESTRICTED, adultClosed.reason());
    }

    /** 八步顺序不可交换：步骤 1 > 2 > 3 > 4。 */
    @Test
    void eightStepShortCircuitOrderIsFixed() {
        // 同时命中步骤 1（权威读取失败）与步骤 2（治理限制）→ 步骤 1 优先
        VisibilityDecision stepOneFirst = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, V1, POLICY_REVISION,
                        true, false, true, V1, "WF-1",
                        Set.of(RestrictionKind.TEMPORARY),
                        BusinessState.REMOVED, BusinessState.REMOVED, AccountStatus.SUSPENDED,
                        AgeBand.C0, Grade.A, VersionState.REJECTED, WorkflowState.REJECTED,
                        false, false));
        assertEquals(VisibilityReason.INCONSISTENT, stepOneFirst.reason());

        // 权威读取正常，步骤 2（治理限制）优先于步骤 4（引用错配）
        VisibilityDecision stepTwoFirst = VisibilityPolicy.decide(
                copy(baseline(), "OTHER", TARGET_ID, V1, V1, POLICY_REVISION,
                        true, true, true, V1, "WF-1",
                        Set.of(RestrictionKind.TEMPORARY),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.BLOCKED, stepTwoFirst.reason());
        assertFalse(stepTwoFirst.raiseIncident());
    }

    @Test
    void nullInputIsDeniedWithoutVersion() {
        VisibilityDecision decision = VisibilityPolicy.decide(null);
        assertFalse(decision.allowed());
        assertNull(decision.versionId());
    }

    /** 补充：空引用集合、空白标识、仅父作品 DRAFT、A 级双重开启分支。 */
    @Test
    void blankReferencesNullRestrictionsAndAdultEntryAreExplicit() {
        VisibilityDecision nullRestrictions = VisibilityPolicy.decide(
                new VisibilityInput(AgeBand.C3, AccountStatus.ACTIVE, TargetType.WORK, TARGET_ID,
                        BusinessState.ACTIVE, BusinessState.ACTIVE, null,
                        V1, VersionState.APPROVED, Grade.G, "WF-1", WorkflowState.APPROVED,
                        V1, POLICY_REVISION, POLICY_REVISION, V1, TARGET_ID, TARGET_ID,
                        true, true, true, true, true));
        assertTrue(nullRestrictions.allowed());

        VisibilityDecision blankCandidate = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, "  ", POLICY_REVISION,
                        true, true, true, V1, "WF-1", Set.of(),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.INCONSISTENT, blankCandidate.reason());

        VisibilityDecision nullCandidate = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, null, POLICY_REVISION,
                        true, true, true, V1, "WF-1", Set.of(),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.INCONSISTENT, nullCandidate.reason());

        VisibilityDecision parentDraftOnly = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, V1, POLICY_REVISION,
                        true, true, true, V1, "WF-1", Set.of(),
                        BusinessState.ACTIVE, BusinessState.DRAFT, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.G, VersionState.APPROVED, WorkflowState.APPROVED,
                        false, false));
        assertEquals(VisibilityReason.INCONSISTENT, parentDraftOnly.reason());

        // 纯函数分支覆盖；P0 契约 AccessPolicy.adultContentEnabled 恒 [false]，运行时不会走此路径
        VisibilityDecision adultConfirmed = VisibilityPolicy.decide(
                copy(baseline(), TARGET_ID, TARGET_ID, V1, V1, POLICY_REVISION,
                        true, true, true, V1, "WF-1", Set.of(),
                        BusinessState.ACTIVE, BusinessState.ACTIVE, AccountStatus.ACTIVE,
                        AgeBand.C3, Grade.A, VersionState.APPROVED, WorkflowState.APPROVED,
                        true, true));
        assertTrue(adultConfirmed.allowed());
    }
}
