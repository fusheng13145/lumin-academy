package com.leyon.lumin.foundation.values;

/** 审核案件状态（03 §2.4 review_case）：QUEUED→CLAIMED→WAITING_SECOND→…→DECIDED。 */
public enum ReviewCaseState {
    QUEUED,
    CLAIMED,
    WAITING_SECOND,
    DECIDED
}
