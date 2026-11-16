package com.leyon.lumin.authoring.domain;

import com.leyon.lumin.catalog.domain.TargetRef;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.CopyrightStatus;
import com.leyon.lumin.foundation.values.DraftState;
import com.leyon.lumin.identity.domain.AgeAssessment;

/**
 * 草稿提交前提闸门（03 §2.2 提交约束 / §4.1 第 2 条；R03）。
 *
 * <p>判定顺序固定：①目标结构（P1001）→ ②草稿态必须 EDITABLE（B2001，SUBMITTED
 * 原草稿冻结、DISCARDED 终态）→ ③版权必须 VALID（B2001）→ ④同目标不得有另一个
 * 未结束发布流程（B2001）→ ⑤作者年龄/监护（沿用 {@link AgeAssessment} 的
 * canPublish 与 denialCode，缺省 A2002）。
 *
 * <p>归属校验与角色互斥由服务层经 {@code RolePolicy} 与仓储完成（INV-07：
 * 角色/年龄从权威记录读取），不在本闸门重复；提交锁行、幂等与 Outbox 同事务
 * 由服务层保证（INV-06）。
 */
public final class SubmissionGate {

    private SubmissionGate() {
    }

    public static ErrorCode check(TargetRef target, DraftState draftState,
                                  CopyrightStatus copyrightStatus,
                                  AgeAssessment authorAgeAssessment,
                                  boolean hasOpenWorkflowOnTarget) {
        if (target == null || target.validate() != ErrorCode.OK) {
            return ErrorCode.P1001;
        }
        if (draftState == null || copyrightStatus == null || authorAgeAssessment == null) {
            return ErrorCode.P1001;
        }
        if (draftState != DraftState.EDITABLE) {
            return ErrorCode.B2001;
        }
        if (copyrightStatus != CopyrightStatus.VALID) {
            return ErrorCode.B2001;
        }
        if (hasOpenWorkflowOnTarget) {
            return ErrorCode.B2001;
        }
        if (!authorAgeAssessment.canPublish()) {
            return authorAgeAssessment.denialCode() == null
                    ? ErrorCode.A2002 : authorAgeAssessment.denialCode();
        }
        return ErrorCode.OK;
    }
}
