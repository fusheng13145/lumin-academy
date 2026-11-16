package com.leyon.lumin.authoring.domain;

import com.leyon.lumin.catalog.domain.TargetRef;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.values.CopyrightStatus;
import com.leyon.lumin.foundation.values.DraftState;
import com.leyon.lumin.foundation.values.TargetType;
import com.leyon.lumin.identity.domain.AgeAssessment;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 草稿提交前提闸门（03 §2.2 提交约束 / §4.1 第 2 条；T07/T09 前置）。 */
class SubmissionGateTest {

    private static final UUID WORK_ID =
            UUID.fromString("3f2a1b4c-5d6e-4f70-8a90-1b2c3d4e5f60");
    private static final TargetRef WORK_TARGET =
            new TargetRef(TargetType.WORK, WORK_ID, null);

    /** 可发布的已核验成年作者评估。 */
    private static AgeAssessment publishable() {
        return new AgeAssessment(AgeBand.C3, true, false, false, false, false, null);
    }

    /** 不可发布的未成年作者评估（带 A2002 拒绝码）。 */
    private static AgeAssessment minorBlocked() {
        return new AgeAssessment(AgeBand.C1, false, true, true, false, false,
                ErrorCode.A2002);
    }

    @Test
    void validSubmissionPasses() {
        assertEquals(ErrorCode.OK, SubmissionGate.check(
                WORK_TARGET, DraftState.EDITABLE, CopyrightStatus.VALID,
                publishable(), false));
    }

    @Test
    void onlyEditableDraftCanBeSubmitted() {
        // 03 §2.2：SUBMITTED 原草稿冻结、DISCARDED 终态——提交事件均拒绝
        assertEquals(ErrorCode.B2001, SubmissionGate.check(
                WORK_TARGET, DraftState.SUBMITTED, CopyrightStatus.VALID,
                publishable(), false));
        assertEquals(ErrorCode.B2001, SubmissionGate.check(
                WORK_TARGET, DraftState.DISCARDED, CopyrightStatus.VALID,
                publishable(), false));
    }

    @Test
    void terminatedCopyrightBlocksSubmission() {
        assertEquals(ErrorCode.B2001, SubmissionGate.check(
                WORK_TARGET, DraftState.EDITABLE, CopyrightStatus.TERMINATED,
                publishable(), false));
    }

    @Test
    void openWorkflowOnSameTargetBlocksResubmission() {
        // 03 §2.2：同目标不得有另一个未结束流程；重提必须新版本/新流程（INV-01）
        assertEquals(ErrorCode.B2001, SubmissionGate.check(
                WORK_TARGET, DraftState.EDITABLE, CopyrightStatus.VALID,
                publishable(), true));
    }

    @Test
    void ageAssessmentDenialCodeIsPropagated() {
        assertEquals(ErrorCode.A2002, SubmissionGate.check(
                WORK_TARGET, DraftState.EDITABLE, CopyrightStatus.VALID,
                minorBlocked(), false));
        // denialCode 缺失时缺省 A2002（05 §5：当前年龄或监护状态不允许此操作）
        assertEquals(ErrorCode.A2002, SubmissionGate.check(
                WORK_TARGET, DraftState.EDITABLE, CopyrightStatus.VALID,
                new AgeAssessment(AgeBand.C1, false, true, true, false, false, null),
                false));
    }

    @Test
    void structuralProblemsAreP1001() {
        assertEquals(ErrorCode.P1001, SubmissionGate.check(
                new TargetRef(TargetType.WORK, WORK_ID, WORK_ID),
                DraftState.EDITABLE, CopyrightStatus.VALID, publishable(), false));
        assertEquals(ErrorCode.P1001, SubmissionGate.check(
                null, DraftState.EDITABLE, CopyrightStatus.VALID, publishable(), false));
        assertEquals(ErrorCode.P1001, SubmissionGate.check(
                WORK_TARGET, null, CopyrightStatus.VALID, publishable(), false));
        assertEquals(ErrorCode.P1001, SubmissionGate.check(
                WORK_TARGET, DraftState.EDITABLE, null, publishable(), false));
        assertEquals(ErrorCode.P1001, SubmissionGate.check(
                WORK_TARGET, DraftState.EDITABLE, CopyrightStatus.VALID, null, false));
    }
}
