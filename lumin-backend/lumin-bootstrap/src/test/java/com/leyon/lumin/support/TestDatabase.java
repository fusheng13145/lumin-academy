package com.leyon.lumin.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
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
    private static final String MIGRATION_DIR = "classpath:db/migration/*.sql";

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
            // 按文件名顺序执行全部迁移脚本（V1 → V1.1 → …）
            org.springframework.core.io.Resource[] scripts;
            try {
                scripts = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                        .getResources(MIGRATION_DIR);
            } catch (IOException e) {
                throw new IllegalStateException("迁移脚本目录解析失败：" + MIGRATION_DIR, e);
            }
            // 按数值版本序（V1 → V2 → … → V10+）：文件名字典序会把 V10/V11 排在
            // V1 之前（'0' < '_'）——外键引用未建表即失败（增量三十四教训登记）
            java.util.Arrays.sort(scripts, java.util.Comparator.comparing(
                    org.springframework.core.io.Resource::getFilename,
                    TestDatabase::byMigrationVersion));
            for (org.springframework.core.io.Resource script : scripts) {
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(script, StandardCharsets.UTF_8));
            }
        } catch (SQLException | org.springframework.jdbc.datasource.init.ScriptException e) {
            throw new IllegalStateException("测试数据库初始化失败：" + e.getMessage(), e);
        }
    }

    /** 供测试类直接建业务级连接（会话时区强制 UTC）。 */
    public static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, username(), password());
    }

    /** 迁移文件名按数值版本序比较（V2 < V10；同版本按文件名）。 */
    static int byMigrationVersion(String left, String right) {
        long l = leadingVersion(left);
        long r = leadingVersion(right);
        if (l != r) {
            return Long.compare(l, r);
        }
        return left.compareTo(right);
    }

    /** 提取文件名前导数值（V10__x.sql → 10；无前导数字 → 0）。 */
    private static long leadingVersion(String filename) {
        String name = filename;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int vStart = 1; // 'V' 之后
        if (!name.startsWith("V") || name.length() <= vStart) {
            return 0;
        }
        int end = vStart;
        while (end < name.length() && Character.isDigit(name.charAt(end))) {
            end++;
        }
        if (end == vStart) {
            return 0;
        }
        try {
            return Long.parseLong(name.substring(vStart, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
