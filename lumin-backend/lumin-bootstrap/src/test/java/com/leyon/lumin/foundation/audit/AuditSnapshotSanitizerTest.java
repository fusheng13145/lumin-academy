package com.leyon.lumin.foundation.audit;

import com.leyon.lumin.audit.service.AuditSnapshotSanitizer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计快照字段白名单（2026-11-20 审查 18 项）：纯函数取证。
 *
 * <p>审计表 append-only（写入即无法删除），因此这里断言的是"**写不进去**"：
 * 口令/令牌/邮箱/生日等键名一律被丢弃，只留计数。
 */
class AuditSnapshotSanitizerTest {

    @Test
    void keepsWhitelistedStateFieldsUnchanged() {
        Map<String, String> sanitized = AuditSnapshotSanitizer.sanitize(
                Map.of("restrictionState", "ACTIVE"));
        assertEquals(Map.of("restrictionState", "ACTIVE"), sanitized);
        assertEquals(Map.of("businessState", "PUBLISHED"),
                AuditSnapshotSanitizer.sanitize(Map.of("businessState", "PUBLISHED")));
    }

    @Test
    void dropsCredentialAndPersonalFields() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("restrictionState", "ACTIVE");
        input.put("password", "Lumin#2026Safe");
        input.put("passwordHash", "$2a$12$abcdef");
        input.put("accessToken", "6f1e...token");
        input.put("email", "reader@example.com");
        input.put("birthDate", "2008-01-01");
        input.put("birthDateCipher", "BASE64CIPHER");
        input.put("totpSecret", "JBSWY3DPEHPK3PXP");
        Map<String, String> sanitized = AuditSnapshotSanitizer.sanitize(input);
        assertEquals("ACTIVE", sanitized.get("restrictionState"));
        // 敏感键一律不存在（不是"被遮成 ***"，是根本不进库）
        assertTrue(!sanitized.containsKey("password"));
        assertTrue(!sanitized.containsKey("passwordHash"));
        assertTrue(!sanitized.containsKey("accessToken"));
        assertTrue(!sanitized.containsKey("email"));
        assertTrue(!sanitized.containsKey("birthDate"));
        assertTrue(!sanitized.containsKey("birthDateCipher"));
        assertTrue(!sanitized.containsKey("totpSecret"));
        // 被拦下的字段数可审计（7 个）
        assertEquals("7", sanitized.get(AuditSnapshotSanitizer.DROPPED_KEY));
        // 序列化结果里不得出现任何敏感值
        String json = sanitized.toString();
        assertTrue(!json.contains("Lumin#2026Safe"));
        assertTrue(!json.contains("reader@example.com"));
        assertTrue(!json.contains("JBSWY3DPEHPK3PXP"));
    }

    @Test
    void truncatesLongValuesAndCapsEntryCount() {
        String longValue = "A".repeat(500);
        Map<String, String> sanitized = AuditSnapshotSanitizer.sanitize(
                Map.of("configState", longValue));
        String value = sanitized.get("configState");
        assertEquals(AuditSnapshotSanitizer.MAX_VALUE_LENGTH + 1, value.length());
        assertTrue(value.endsWith("…"));

        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            many.put("unknownField" + i, "v" + i);
        }
        Map<String, String> capped = AuditSnapshotSanitizer.sanitize(many);
        // 全部不在白名单 → 只剩计数
        assertEquals("30", capped.get(AuditSnapshotSanitizer.DROPPED_KEY));
        assertEquals(1, capped.size());
    }

    @Test
    void emptySnapshotIsNullNotNullEmptyMap() {
        assertNull(AuditSnapshotSanitizer.sanitize(null));
        assertNull(AuditSnapshotSanitizer.sanitize(Map.of()));
        // 全部非白名单：只回一条计数，不回空 Map（让"被拦了 1 个字段"可审计）
        Map<String, String> onlyDropped = AuditSnapshotSanitizer.sanitize(
                Map.of("role", "ADMIN"));
        assertEquals(Map.of(AuditSnapshotSanitizer.DROPPED_KEY, "1"), onlyDropped);
    }
}
