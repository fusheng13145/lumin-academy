package com.leyon.lumin.billing_reserved;

import java.util.List;

/** 付费骨架表规格；三个开关在 P0 恒为「不暴露 / 不建表 / 无创建者」。 */
public record BillingTableSpec(
        String tableName,
        List<BillingColumnSpec> columns,
        List<String> constraints,
        boolean exposedViaHttp,
        boolean ddlAllowed,
        boolean creatorPresent) {
}
