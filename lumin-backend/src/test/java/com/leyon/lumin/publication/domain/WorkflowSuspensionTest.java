package com.leyon.lumin.publication.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.SuspensionReason;
import com.leyon.lumin.foundation.values.WorkflowState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 发布流程挂起原因一致性（03 §2.3 / 04 §4 publication_workflow.suspended_reason）。 */
class WorkflowSuspensionTest {

    private static final List<WorkflowState> NON_SUSPENDED = List.of(
            WorkflowState.SUBMITTED, WorkflowState.AUTO_REVIEW, WorkflowState.HUMAN_REVIEW,
            WorkflowState.APPROVED, WorkflowState.REJECTED, WorkflowState.CANCELLED);

    @Test
    void suspendedRequiresReason() {
        assertEquals(ErrorCode.P1001, WorkflowSuspension.validate(
                WorkflowState.SUSPENDED, null));
        for (SuspensionReason reason : SuspensionReason.values()) {
            assertEquals(ErrorCode.OK, WorkflowSuspension.validate(
                    WorkflowState.SUSPENDED, reason), reason.name());
        }
    }

    @Test
    void nonSuspendedMustNotCarryStaleReason() {
        for (WorkflowState state : NON_SUSPENDED) {
            assertEquals(ErrorCode.OK, WorkflowSuspension.validate(state, null), state.name());
            assertEquals(ErrorCode.P1001, WorkflowSuspension.validate(
                    state, SuspensionReason.MACHINE_TIMEOUT), state.name());
        }
    }

    @Test
    void nullStateIsRejected() {
        assertEquals(ErrorCode.P1001, WorkflowSuspension.validate(null, null));
        assertEquals(ErrorCode.P1001, WorkflowSuspension.validate(
                null, SuspensionReason.MACHINE_TIMEOUT));
    }
}
