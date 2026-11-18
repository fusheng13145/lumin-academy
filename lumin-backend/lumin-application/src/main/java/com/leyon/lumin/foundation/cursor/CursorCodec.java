package com.leyon.lumin.foundation.cursor;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 列表游标编解码（04 §7 / 05 §2）：游标 = 排序键 + id + 过滤条件摘要 + 主体作用域，
 * 带 HMAC 签名；默认 limit 20、最大 100；过期游标 15 分钟；过滤器改变必须重新分页。
 *
 * <p>令牌形态：{@code base64url(payload).base64url(HMAC-SHA256(payload))}，全 ASCII、
 * 最长 512 字符。payload 字段以 U+0001 控制字符分隔（经 {@code (char) 0x01} 常量构造，
 * 避开源码中的 Unicode 逃逸预处理），不会出现在合法字段值中。
 * 签名比较使用 {@link MessageDigest#isEqual} 恒定时间比较；签名密钥由
 * Secret Manager 提供，缺失属部署错误，编码时抛出（fail-fast）。
 */
public final class CursorCodec {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_TOKEN_LENGTH = 512;
    /** 游标有效期 15 分钟（04 §7 / 05 §2）。 */
    public static final Duration VALIDITY = Duration.ofMinutes(15);

    private static final char UNIT_SEPARATOR = (char) 0x01;
    private static final String UNIT_SEPARATOR_LITERAL = String.valueOf(UNIT_SEPARATOR);
    private static final int PAYLOAD_FIELDS = 5;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private CursorCodec() {
    }

    /** 游标载荷（decode 成功时返回）。 */
    public record Payload(String sortKeyValue, UUID lastId, String filterDigest,
                          UUID subjectId, Instant issuedAt) {
    }

    /** 解码结果：code = OK 时 payload 可用；否则一律 400/P1001（05 §2 失效游标）。 */
    public record DecodedCursor(Payload payload, ErrorCode code) {
    }

    /** limit 解析：null → 默认 20；范围外静默钳制到 1–100（用于内部纠偏）。 */
    public static int resolveLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(requested, 1), MAX_LIMIT);
    }

    /** limit 校验：显式传入 0 / 负数 / 超上限 → 400/P1001（显式非法值不静默钳制）。 */
    public static ErrorCode validateLimit(Integer requested) {
        if (requested == null) {
            return ErrorCode.OK;
        }
        return requested >= 1 && requested <= MAX_LIMIT ? ErrorCode.OK : ErrorCode.P1001;
    }

    /** 过滤条件摘要：对规范化过滤串计算 SHA-256（小写十六进制），由服务层传入。 */
    public static String filterDigest(String canonicalFilter) {
        try {
            byte[] bytes = (canonicalFilter == null ? "" : canonicalFilter)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    /** 编码游标；signingKey 为 null / 空 → IllegalStateException（部署错误，fail-fast）。 */
    public static String encode(Payload payload, byte[] signingKey) {
        if (signingKey == null || signingKey.length == 0) {
            throw new IllegalStateException("游标签名密钥缺失（Secret Manager 注入失败）");
        }
        if (payload == null || payload.sortKeyValue() == null || payload.lastId() == null
                || payload.filterDigest() == null || payload.subjectId() == null
                || payload.issuedAt() == null
                || payload.sortKeyValue().indexOf(UNIT_SEPARATOR) >= 0) {
            throw new IllegalArgumentException("游标载荷字段缺失或含分隔符");
        }
        String canonical = canonicalOf(payload);
        byte[] signature = hmac(canonical.getBytes(StandardCharsets.UTF_8), signingKey);
        return B64_ENC.encodeToString(canonical.getBytes(StandardCharsets.UTF_8))
                + "." + B64_ENC.encodeToString(signature);
    }

    /**
     * 解码并验证：长度、签名（恒定时间）、字段完整性、15 分钟有效期、过滤摘要一致性。
     * subjectId 与当前请求主体的比对由服务层执行（主体作用域绑定）。
     */
    public static DecodedCursor decode(String token, byte[] signingKey, Instant now,
                                       String expectedFilterDigest) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH
                || signingKey == null || signingKey.length == 0) {
            return new DecodedCursor(null, ErrorCode.P1001);
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return new DecodedCursor(null, ErrorCode.P1001);
        }
        String canonical;
        try {
            byte[] payloadBytes = B64_DEC.decode(token.substring(0, dot));
            byte[] signatureBytes = B64_DEC.decode(token.substring(dot + 1));
            canonical = new String(payloadBytes, StandardCharsets.UTF_8);
            byte[] expectedSignature =
                    hmac(canonical.getBytes(StandardCharsets.UTF_8), signingKey);
            if (!MessageDigest.isEqual(expectedSignature, signatureBytes)) {
                return new DecodedCursor(null, ErrorCode.P1001);
            }
        } catch (IllegalArgumentException e) {
            return new DecodedCursor(null, ErrorCode.P1001);
        }
        String[] fields = canonical.split(UNIT_SEPARATOR_LITERAL, -1);
        if (fields.length != PAYLOAD_FIELDS) {
            return new DecodedCursor(null, ErrorCode.P1001);
        }
        UUID lastId;
        UUID subjectId;
        Instant issuedAt;
        try {
            lastId = UUID.fromString(fields[1]);
            subjectId = UUID.fromString(fields[3]);
            issuedAt = Instant.ofEpochMilli(Long.parseLong(fields[4]));
        } catch (IllegalArgumentException e) {
            return new DecodedCursor(null, ErrorCode.P1001);
        }
        if (issuedAt.plus(VALIDITY).isBefore(now)) {
            return new DecodedCursor(null, ErrorCode.P1001);
        }
        if (expectedFilterDigest != null && !expectedFilterDigest.equals(fields[2])) {
            // 过滤器改变必须重新分页（04 §7）
            return new DecodedCursor(null, ErrorCode.P1001);
        }
        return new DecodedCursor(new Payload(fields[0], lastId, fields[2], subjectId, issuedAt),
                ErrorCode.OK);
    }

    private static String canonicalOf(Payload payload) {
        return payload.sortKeyValue()
                + UNIT_SEPARATOR_LITERAL + payload.lastId()
                + UNIT_SEPARATOR_LITERAL + payload.filterDigest()
                + UNIT_SEPARATOR_LITERAL + payload.subjectId()
                + UNIT_SEPARATOR_LITERAL + payload.issuedAt().toEpochMilli();
    }

    private static byte[] hmac(byte[] data, byte[] signingKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("JDK 缺少 HmacSHA256 实现", e);
        }
    }
}
