package com.leyon.lumin.billing_reserved;

/**
 * 04 §6.2 付费权限三码：仅登记字典，不授予、不暴露接口。
 * P0 不执行 B1–B6 / R1–R10 / A4（01 DEC-06、07 §6）。
 */
public enum BillingPermission {
    BILLING_PROPOSE,
    BILLING_APPROVE,
    SETTLEMENT_EXECUTE
}
