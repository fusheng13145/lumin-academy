package com.leyon.lumin.foundation.values;

/** 发布流程态 7 态（03 §2.3、契约 WorkflowState）。 */
public enum WorkflowState {
    SUBMITTED,
    AUTO_REVIEW,
    HUMAN_REVIEW,
    APPROVED,
    REJECTED,
    SUSPENDED,
    CANCELLED;

    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == CANCELLED;
    }
}
