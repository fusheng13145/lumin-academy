package com.leyon.lumin.billing_reserved;

/** 付费骨架字段规格（04 §6.2）：只读文本描述，不执行 DDL、不建表。 */
public record BillingColumnSpec(
        String name,
        String sqlType,
        boolean nullable,
        boolean appendOnly,
        String enumWhitelist,
        String checkConstraint) {
}
