package com.leyon.lumin.foundation.values;

/** 治理限制类型（04 §5 restriction）；FINAL_VIOLATION / CONTRACT_TERMINATED 不可恢复传播。 */
public enum RestrictionKind {
    TEMPORARY,
    FINAL_VIOLATION,
    CONTRACT_TERMINATED,
    GUARDIAN_REVOKED
}
