package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 持久化集成测试基类：真实 MySQL（lumin_test 专用库）。
 * 凭据未配置时全部测试按 Assumption 跳过——**诚实登记，不伪造通过**。
 * 每个测试独立重建 schema（07 §1.1：用例互不依赖运行顺序）。
 */
@SpringBootTest
public abstract class MySqlIntegrationBase {

    @BeforeEach
    void recreateTestDatabase() {
        org.junit.jupiter.api.Assumptions.assumeTrue(TestDatabase.isConfigured(),
                "LUMIN_TEST_DB_PASSWORD 未配置：持久化集成测试跳过（07 §1.2 真实数据库要求，"
                        + "不用 H2 替代）");
        TestDatabase.recreateSchema();
    }
}
