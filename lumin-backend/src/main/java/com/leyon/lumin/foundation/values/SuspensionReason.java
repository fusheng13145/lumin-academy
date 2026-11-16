package com.leyon.lumin.foundation.values;

/** 发布流程挂起原因（03 §2.3：超时/契约无效/预算耗尽 → SUSPENDED；04 §4 publication_workflow.suspended_reason）。 */
public enum SuspensionReason {
    MACHINE_TIMEOUT,
    CONTRACT_INVALID,
    BUDGET_EXHAUSTED
}
