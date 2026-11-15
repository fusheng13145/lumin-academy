package com.leyon.lumin.billing_reserved;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T38（07 §2.4）：04 §6.2 六张表字段/约束、金额 0/1/MAX/MAX+1/负数边界、
 * 无 HTTP 入口、权限未授予；不执行 B1–B6 / R1–R10 / A4。
 */
class BillingModelTest {

    private static Stream<Arguments> tableExpectations() {
        return Stream.of(
                Arguments.of("billing_account",
                        List.of("owner_id", "kind", "currency", "enabled")),
                Arguments.of("billing_order",
                        List.of("buyer_id", "product_type", "product_id", "amount_minor",
                                "currency", "idempotency_key", "state")),
                Arguments.of("ledger_entry",
                        List.of("transaction_id", "account_id", "direction", "amount_minor",
                                "currency", "reversal_of")),
                Arguments.of("revenue_share",
                        List.of("order_id", "beneficiary_id", "rule_revision",
                                "amount_minor", "state")),
                Arguments.of("settlement",
                        List.of("author_id", "period_start", "period_end", "net_minor", "state")),
                Arguments.of("refund",
                        List.of("order_id", "amount_minor", "reason", "state",
                                "requested_by", "approved_by"))
        );
    }

    /** U-01：04 §6.2 恰好 6 张表，不存在「第 7 张」。 */
    @Test
    void registryContainsExactlySixTables() {
        assertEquals(6, BillingModelRegistry.TABLE_NAMES.size());
        assertEquals(6, BillingModelRegistry.allTables().size());
        assertEquals(Set.copyOf(BillingModelRegistry.TABLE_NAMES),
                BillingModelRegistry.allTables().stream()
                        .map(BillingTableSpec::tableName).collect(Collectors.toSet()));
    }

    @ParameterizedTest
    @MethodSource("tableExpectations")
    void eachTableHasExactColumnsAndReadOnlyFlags(String name, List<String> expectedColumns) {
        BillingTableSpec table = BillingModelRegistry.table(name);
        assertNotNull(table, "缺少 04 §6.2 的表：" + name);
        assertEquals(name, table.tableName());
        assertEquals(Set.copyOf(expectedColumns),
                table.columns().stream().map(BillingColumnSpec::name).collect(Collectors.toSet()),
                name + " 字段集合必须与 04 §6.2 一致");
        assertFalse(table.exposedViaHttp(), name + " 不得暴露 HTTP 入口");
        assertFalse(table.ddlAllowed(), name + " 不得执行 DDL");
        assertFalse(table.creatorPresent(), name + " P0 无创建者");
        assertFalse(table.constraints().isEmpty(), name + " 必须登记约束");
        for (BillingColumnSpec column : table.columns()) {
            assertNotNull(column.sqlType());
            assertNotNull(column.enumWhitelist());
            assertNotNull(column.checkConstraint());
        }
    }

    @Test
    void onlySettlementNetMinorAllowsNegativeAmount() {
        BillingTableSpec settlement = BillingModelRegistry.table("settlement");
        assertNotNull(settlement);
        assertTrue(settlement.constraints().stream().anyMatch(c -> c.contains("net_minor")),
                "净额允许负值须在 settlement 约束中登记");
        for (String name : BillingModelRegistry.TABLE_NAMES) {
            if ("settlement".equals(name)) {
                continue;
            }
            BillingTableSpec table = BillingModelRegistry.table(name);
            assertNotNull(table);
            assertTrue(table.constraints().stream().noneMatch(c -> c.contains("允许负值")),
                    name + " 不得允许负金额");
        }
    }

    @Test
    void moneyMinorBoundariesAreExact() {
        assertEquals(0L, MoneyMinor.zero().minor());
        assertEquals(0L, MoneyMinor.ofNonNegative(0).minor());
        assertEquals(1L, MoneyMinor.ofNonNegative(1).minor());
        assertEquals(MoneyMinor.MAX, MoneyMinor.ofNonNegative(MoneyMinor.MAX).minor());
        assertEquals(9007199254740991L, MoneyMinor.MAX, "2^53-1，与三端 JSON 精度一致");
        assertTrue(MoneyMinor.ofNonNegative(MoneyMinor.MAX).withinJsSafeInteger());

        assertThrows(IllegalArgumentException.class,
                () -> MoneyMinor.ofNonNegative(MoneyMinor.MAX + 1));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyMinor.ofNonNegative(-1));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyMinor.ofSigned(MoneyMinor.MAX + 1));
        assertThrows(IllegalArgumentException.class,
                () -> MoneyMinor.ofSigned(-MoneyMinor.MAX - 1));

        assertEquals(-1L, MoneyMinor.ofSigned(-1).minor());
        assertEquals(-MoneyMinor.MAX, MoneyMinor.ofSigned(-MoneyMinor.MAX).minor());
    }

    @Test
    void noHttpEntryNoTradingAndPermissionsOnlyRegistered() {
        assertFalse(BillingModelRegistry.hasHttpEntry(), "P0 无交易 HTTP 入口");
        assertFalse(BillingModelRegistry.isTradingEnabled(), "交易未启用");
        assertEquals(3, BillingModelRegistry.registeredPermissions().size());
        assertEquals(Set.of(BillingPermission.BILLING_PROPOSE,
                        BillingPermission.BILLING_APPROVE,
                        BillingPermission.SETTLEMENT_EXECUTE),
                BillingModelRegistry.registeredPermissions(),
                "三权限仅登记字典，不授予任何主体");
    }

    @Test
    void unknownTableReturnsNull() {
        assertEquals(null, BillingModelRegistry.table("bookmark"),
                "04 §6.1 的 P1 关系表不在付费骨架中");
    }

    /** 补充：金额构造器的上界/下界短路与零值复用。 */
    @Test
    void moneyMinorSignedBoundariesAndHelpersAreExplicit() {
        assertTrue(MoneyMinor.ofSigned(0).withinJsSafeInteger());
        assertTrue(MoneyMinor.ofSigned(-MoneyMinor.MAX).withinJsSafeInteger());
        assertTrue(MoneyMinor.ofSigned(MoneyMinor.MAX).withinJsSafeInteger());
        assertThrows(IllegalArgumentException.class, () -> new MoneyMinor(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new MoneyMinor(Long.MIN_VALUE));
        assertEquals(MoneyMinor.ZERO, MoneyMinor.zero());
        assertEquals(0L, MoneyMinor.ZERO.minor());
    }
}
