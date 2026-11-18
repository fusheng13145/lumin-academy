package com.leyon.lumin.foundation.values;

/** 申诉状态（03 §2.4 appeal）：OPEN→IN_REVIEW→UPHELD/OVERTURNED，两个终态。 */
public enum AppealState {
    OPEN,
    IN_REVIEW,
    UPHELD,
    OVERTURNED
}
