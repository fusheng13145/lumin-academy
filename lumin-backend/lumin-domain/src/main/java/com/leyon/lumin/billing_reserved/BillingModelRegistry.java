package com.leyon.lumin.billing_reserved;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 付费实体骨架只读注册（04 §6.2、T38、U-01）。
 *
 * <p><strong>空表 ≠ 交易已可用</strong>：只以静态 record 描述 04 §6.2 的恰好
 * <strong>6 张表</strong>字段与约束；不执行 DDL、不建表、不写库、不暴露交易入口、
 * 不授予权限，不执行 B1–B6 / R1–R10 / A4（01 DEC-06、07 §6）。
 */
public final class BillingModelRegistry {

    /** 04 §6.2 原文六张表；04 §6.1 的三张 P1 关系表不在本骨架内。 */
    public static final List<String> TABLE_NAMES = List.of(
            "billing_account", "billing_order", "ledger_entry",
            "revenue_share", "settlement", "refund");

    private static final String CNY = "CNY";
    private static final String NON_NEGATIVE = "amount>=0";
    private static final String POSITIVE = "amount>0";
    private static final String NONE = "";

    private static final List<BillingTableSpec> TABLES = buildTables();

    private BillingModelRegistry() {
    }

    private static List<BillingTableSpec> buildTables() {
        return List.of(
                new BillingTableSpec("billing_account", List.of(
                        column("owner_id", "UUID", false, "owner_id"),
                        column("kind", "VARCHAR(24)", false,
                                "USER_CASH/COIN_LIABILITY/PLATFORM_REVENUE/AUTHOR_PAYABLE/"
                                        + "TAX_PAYABLE/REFUND_RESERVE/CHARGEBACK_LOSS/"
                                        + "INCENTIVE_EXPENSE/CHANNEL_COST"),
                        column("currency", "CHAR(3)", false, CNY),
                        column("enabled", "BOOLEAN", false, "enabled 默认 false")),
                        List.of("U(owner_id,kind,currency)", "enabled 默认 false"),
                        false, false, false),

                new BillingTableSpec("billing_order", List.of(
                        column("buyer_id", "UUID", false, "buyer_id"),
                        column("product_type", "VARCHAR(16)", false,
                                "CHAPTER/BOOK/MEMBERSHIP/GIFT"),
                        column("product_id", "UUID", false, "product_id"),
                        column("amount_minor", "BIGINT", false, NON_NEGATIVE),
                        column("currency", "CHAR(3)", false, CNY),
                        column("idempotency_key", "UUID", false, "idempotency_key"),
                        column("state", "VARCHAR(16)", false,
                                "CREATED/PAID/CANCELLED/REFUNDED")),
                        List.of(NON_NEGATIVE, "P0 无创建者"),
                        false, false, false),

                new BillingTableSpec("ledger_entry", List.of(
                        column("transaction_id", "UUID", true, "transaction_id"),
                        column("account_id", "UUID", true, "account_id FK"),
                        column("direction", "VARCHAR(8)", true, "DEBIT/CREDIT"),
                        column("amount_minor", "BIGINT", true, POSITIVE),
                        column("currency", "CHAR(3)", true, CNY),
                        column("reversal_of", "UUID", true, "reversal_of FK 可空")),
                        List.of(POSITIVE, "同事务借贷总额相等为 B 类写入门禁", "append-only"),
                        false, false, false),

                new BillingTableSpec("revenue_share", List.of(
                        column("order_id", "UUID", false, "order_id FK"),
                        column("beneficiary_id", "UUID", false, "beneficiary_id"),
                        column("rule_revision", "BIGINT", false, "rule_revision"),
                        column("amount_minor", "BIGINT", false, NON_NEGATIVE),
                        column("state", "VARCHAR(16)", false,
                                "PENDING/SETTLED/FROZEN/REVERSED")),
                        List.of(NON_NEGATIVE, "P0 仅 schema"),
                        false, false, false),

                new BillingTableSpec("settlement", List.of(
                        column("author_id", "UUID", false, "author_id"),
                        column("period_start", "DATE", false, "period_start"),
                        column("period_end", "DATE", false, "period_end"),
                        column("net_minor", "BIGINT", false,
                                "net_minor 净额允许负值（|x| <= MoneyMinor.MAX）"),
                        column("state", "VARCHAR(16)", false,
                                "DRAFT/APPROVED/PAID/FAILED")),
                        List.of("U(author_id,period_start,period_end)",
                                "net_minor 净额允许负值"),
                        false, false, false),

                new BillingTableSpec("refund", List.of(
                        column("order_id", "UUID", false, "order_id FK"),
                        column("amount_minor", "BIGINT", false, POSITIVE),
                        column("reason", "VARCHAR(500)", false, "reason"),
                        column("state", "VARCHAR(16)", false,
                                "REQUESTED/APPROVED/PAID/REJECTED"),
                        column("requested_by", "UUID", false, "requested_by"),
                        column("approved_by", "UUID", false, "approved_by 可空")),
                        List.of(POSITIVE, "禁止自批"),
                        false, false, false)
        );
    }

    /** appendOnly 仅 ledger_entry 为 true；其余字段为只读描述。 */
    private static BillingColumnSpec column(String name, String sqlType, boolean appendOnly,
                                            String enumOrCheck) {
        boolean nullable = "reversal_of".equals(name) || "approved_by".equals(name);
        String whitelist = enumOrCheck.contains("/") ? enumOrCheck : NONE;
        String check = enumOrCheck.contains("amount") || enumOrCheck.contains("net_minor")
                ? enumOrCheck : NONE;
        return new BillingColumnSpec(name, sqlType, nullable, appendOnly, whitelist, check);
    }

    public static List<BillingTableSpec> allTables() {
        return TABLES;
    }

    /** 未登记的表名返回 null（例如 04 §6.1 的 P1 关系表）。 */
    public static BillingTableSpec table(String name) {
        for (BillingTableSpec spec : TABLES) {
            if (spec.tableName().equals(name)) {
                return spec;
            }
        }
        return null;
    }

    /** 三权限仅登记字典，不授予任何主体。 */
    public static Set<BillingPermission> registeredPermissions() {
        return Collections.unmodifiableSet(EnumSet.allOf(BillingPermission.class));
    }

    public static boolean hasHttpEntry() {
        return false;
    }

    public static boolean isTradingEnabled() {
        return false;
    }
}
