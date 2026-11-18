package com.leyon.lumin.foundation.idempotency;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * 幂等作用域、请求摘要与过期（03 §4.1 第 3 条 / 04 §5.1 idempotency_record / 05 §2）。
 *
 * <p>作用域 = subjectId + HTTP 方法 + 规范化路径 + Idempotency-Key；键为 UUID，
 * 保留 24 小时；同键不同请求摘要返回 409/B2003。摘要对请求体原始字节计算——
 * 请求体的规范化（紧凑 JSON、未知字段拒绝）由服务层反序列化保证，此处不做二次变换。
 * 路径规范化为工程候选：去 query/fragment、折叠连续斜杠、去尾部斜杠（根路径除外）。
 * 响应回放由持久化层 idempotency_record 承载，本类只提供规则纯函数。
 */
public final class IdempotencyRules {

    /** 幂等记录保留 24 小时（04 §5.1 / §8）。 */
    public static final Duration TTL = Duration.ofHours(24);

    private static final Set<String> IDEMPOTENT_METHODS = Set.of("POST", "PUT", "PATCH");

    private IdempotencyRules() {
    }

    /** 幂等作用域（对应 idempotency_record 唯一约束 U(subject_id, method, path, key)）。 */
    public record Scope(UUID subjectId, String method, String normalizedPath, UUID key) {
    }

    /** Idempotency-Key 必须为 UUID（05 §2）；返回 {@link ErrorCode#OK} 或 400/P1001。 */
    public static ErrorCode validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ErrorCode.P1001;
        }
        try {
            UUID.fromString(idempotencyKey);
            return ErrorCode.OK;
        } catch (IllegalArgumentException e) {
            return ErrorCode.P1001;
        }
    }

    /** 写幂等仅覆盖 POST / PUT / PATCH（05 §2）。 */
    public static boolean isIdempotentMethod(String method) {
        return method != null && IDEMPOTENT_METHODS.contains(method);
    }

    /**
     * 路径规范化：去 query/fragment（首个 ? 或 # 起截断）、折叠连续斜杠、
     * 去尾部斜杠（根路径除外）。null 返回 null，由调用方按 P1001 拒绝。
     */
    public static String normalizePath(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        int cut = rawPath.length();
        int query = rawPath.indexOf('?');
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        int fragment = rawPath.indexOf('#');
        if (fragment >= 0) {
            cut = Math.min(cut, fragment);
        }
        String path = rawPath.substring(0, cut).replaceAll("/{2,}", "/");
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** 请求摘要：对原始字节计算 SHA-256，小写十六进制（64 位）。 */
    public static String requestDigest(byte[] bodyBytes) {
        if (bodyBytes == null) {
            bodyBytes = new byte[0];
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bodyBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    /** UTF-8 便捷重载（服务层通常持有规范化后的字符串载荷）。 */
    public static String requestDigest(String body) {
        return requestDigest(body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    /** 过期时间 = 创建时间 + 24h。 */
    public static Instant expiresAt(Instant createdAt) {
        if (createdAt == null) {
            return null;
        }
        return createdAt.plus(TTL);
    }

    /** 持久化层便捷重载（DATETIME(6) 行值按 UTC 字面量处理，语义与 Instant 版本一致）。 */
    public static LocalDateTime expiresAt(LocalDateTime createdAt) {
        if (createdAt == null) {
            return null;
        }
        return createdAt.plus(TTL);
    }

    /** 过期判断：保留期内可回放；到期当刻起失效（「保留 24 小时」按 [created, created+24h) 计）。 */
    public static boolean isExpired(Instant expiresAt, Instant now) {
        if (expiresAt == null || now == null) {
            return true;
        }
        return !now.isBefore(expiresAt);
    }

    /** 持久化层便捷重载（语义与 Instant 版本一致）。 */
    public static boolean isExpired(LocalDateTime expiresAt, LocalDateTime now) {
        if (expiresAt == null || now == null) {
            return true;
        }
        return !now.isBefore(expiresAt);
    }
}
