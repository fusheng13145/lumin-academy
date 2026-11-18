package com.leyon.lumin.audit.service;

import com.leyon.lumin.audit.repository.AuditRecordMapper;
import com.leyon.lumin.audit.repository.AuditRecordRow;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 追加式审计适配器（audit 域所有权：02 §3.1；实现 foundation 声明的端口，
 * 各业务模块经端口写审计、不直接访问本模块 Repository）。
 * digest 对规范字段串计算，供定期签名归档（WORM）比对（04 §5.1）。
 */
@Service
public class AuditAppender implements AuditRecorder {

    private final AuditRecordMapper mapper;
    private final ObjectMapper objectMapper;

    public AuditAppender(AuditRecordMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void record(AuditEntry entry) {
        recordTransition(entry, null, null);
    }

    /** 带状态迁移的审计（T35）：digest 覆盖前后脱敏快照；调用方事务承载写入，
     *  审计写失败即高危事务回滚（INV-06 / 03 §6）。 */
    @Override
    @Transactional
    public void recordTransition(AuditEntry entry, java.util.Map<String, String> before,
                                 java.util.Map<String, String> after) {
        LocalDateTime eventAt = LocalDateTime.ofInstant(entry.eventAt(), ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String beforeJson = before == null || before.isEmpty() ? null
                : objectMapper.writeValueAsString(before);
        String afterJson = after == null || after.isEmpty() ? null
                : objectMapper.writeValueAsString(after);
        String digest = sha256(entry.actorId() + "|" + entry.action() + "|"
                + entry.resourceType() + "|" + entry.resourceId() + "|" + eventAt
                + "|" + canonical(before) + "|" + canonical(after));
        mapper.insert(new AuditRecordRow(UUID.randomUUID().toString(), entry.actorId(),
                entry.roleContext(), entry.action(), entry.resourceType(),
                entry.resourceId(), beforeJson, afterJson, entry.reason(), entry.traceId(),
                eventAt, digest, now, now, entry.actorId(), entry.actorId(), 1L));
    }

    /** 规范化前后快照（键排序，供 digest 稳定计算）。 */
    private static String canonical(java.util.Map<String, String> state) {
        if (state == null || state.isEmpty()) {
            return "";
        }
        return state.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }
}
