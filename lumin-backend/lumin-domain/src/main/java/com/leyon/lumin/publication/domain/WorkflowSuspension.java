package com.leyon.lumin.publication.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.SuspensionReason;
import com.leyon.lumin.foundation.values.WorkflowState;

/**
 * 发布流程挂起原因一致性校验（03 §2.3 / 04 §4 publication_workflow.suspended_reason）。
 *
 * <p>SUSPENDED 必须携带原因（超时 / 契约无效 / 预算耗尽，03 §2.3）；
 * 非 SUSPENDED 状态不得残留历史原因——避免恢复路径被旧原因误导。
 * SUSPENDED 恢复始终进入人工队列、不再次自动放行（03 §2.3），
 * 该语义由 {@code PublicationStateMachine} 与路由层保证，本类只做字段一致性。
 */
public final class WorkflowSuspension {

    private WorkflowSuspension() {
    }

    /** 返回 {@link ErrorCode#OK} 表示一致，否则为拒绝错误码（400/P1001）。 */
    public static ErrorCode validate(WorkflowState state, SuspensionReason reason) {
        if (state == null) {
            return ErrorCode.P1001;
        }
        if (state == WorkflowState.SUSPENDED) {
            return reason == null ? ErrorCode.P1001 : ErrorCode.OK;
        }
        return reason == null ? ErrorCode.OK : ErrorCode.P1001;
    }
}
