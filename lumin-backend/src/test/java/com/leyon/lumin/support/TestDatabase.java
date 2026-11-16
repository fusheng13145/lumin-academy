package com.leyon.lumin.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 持久化集成测试的测试库支撑：直连本机真实 MySQL（07 §1.2——不用 H2 替代）。
 * 凭据经环境变量注入（LUMIN_TEST_DB_USERNAME / LUMIN_TEST_DB_PASSWORD），不落盘；
 * 未配置时 {@link #isConfigured()} 返回 false，集成测试按 Assumption 诚实跳过。
 * 仅操作专用测试库 lumin_test（DROP/CREATE 范围严格限定于该库）。
 */
public final class TestDatabase {

    public static final String TEST_DB = "lumin_test";
    private static final String SERVER_URL =
            "jdbc:mysql://127.0.0.1:3306/?connectionTimeZone=UTC&allowPublicKeyRetrieval=true";
    private static final String DB_URL =
            "jdbc:mysql://127.0.0.1:3306/" + TEST_DB
                    + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                    + "&allowPublicKeyRetrieval=true";
    private static final String SCHEMA_LOCATION =
            "db/migration/V1__p0b_core_schema.sql";

    private TestDatabase() {
    }

    private static String username() {
        return System.getenv().getOrDefault("LUMIN_TEST_DB_USERNAME", "root");
    }

    private static String password() {
        return System.getenv("LUMIN_TEST_DB_PASSWORD");
    }

    /** 凭据已配置且本机 MySQL 可连通。 */
    public static boolean isConfigured() {
        String password = password();
        if (password == null || password.isBlank()) {
            return false;
        }
        try (Connection ignored = DriverManager.getConnection(SERVER_URL, username(), password)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** 重建专用测试库并执行 V1 迁移脚本（每个集成测试类独立、可重复）。 */
    public static void recreateSchema() {
        try (Connection connection = DriverManager.getConnection(SERVER_URL, username(), password());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + TEST_DB);
            statement.execute("CREATE DATABASE " + TEST_DB
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            statement.execute("USE " + TEST_DB);
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource(SCHEMA_LOCATION), StandardCharsets.UTF_8));
        } catch (SQLException | org.springframework.jdbc.datasource.init.ScriptException e) {
            throw new IllegalStateException("测试数据库初始化失败：" + e.getMessage(), e);
        }
    }

    /** 供测试类直接建业务级连接（会话时区强制 UTC）。 */
    public static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, username(), password());
    }
}
