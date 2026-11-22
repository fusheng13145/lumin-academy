package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 持久化集成测试基类：真实 MySQL（lumin_test 专用库）。
 *
 * <p>凭据未配置时的行为由 {@code lumin.test.require-db} 决定（2026-11-20 质量门禁修复）：
 * <ul>
 *   <li>本地默认（未设置）：按 Assumption **跳过**并在报告里记为 skipped，便于无库开发；</li>
 *   <li>CI（{@code -Dlumin.test.require-db=true} 或 {@code LUMIN_TEST_REQUIRE_DB=true}）：
 *       **直接失败**——避免"全跳过却显示 BUILD SUCCESS"的假绿（原实现的风险正是如此）。</li>
 * </ul>
 * 每个测试独立重建 schema（07 §1.1：用例互不依赖运行顺序）。
 */
@SpringBootTest
public abstract class MySqlIntegrationBase {

    @BeforeEach
    void recreateTestDatabase() {
        if (!TestDatabase.isConfigured()) {
            boolean required = Boolean.getBoolean("lumin.test.require-db")
                    || "true".equalsIgnoreCase(System.getenv("LUMIN_TEST_REQUIRE_DB"));
            if (required) {
                throw new IllegalStateException("要求真实 MySQL（lumin.test.require-db=true）"
                        + "但未配置 LUMIN_TEST_DB_PASSWORD：拒绝静默跳过——"
                        + "集成测试被跳过不等于通过（07 §1.2）");
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "LUMIN_TEST_DB_PASSWORD 未配置：持久化集成测试跳过（07 §1.2 真实数据库要求，"
                            + "不用 H2 替代）；CI 请设 -Dlumin.test.require-db=true 使之失败");
            return;
        }
        TestDatabase.recreateSchema();
    }
}
