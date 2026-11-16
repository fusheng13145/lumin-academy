package com.leyon.lumin.audit.service;

import com.leyon.lumin.audit.repository.AuditRecordMapper;
import com.leyon.lumin.audit.repository.AuditRecordRow;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public AuditAppender(AuditRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void record(AuditEntry entry) {
        LocalDateTime eventAt = LocalDateTime.ofInstant(entry.eventAt(), ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String digest = sha256(entry.actorId() + "|" + entry.action() + "|"
                + entry.resourceType() + "|" + entry.resourceId() + "|" + eventAt);
        mapper.insert(new AuditRecordRow(UUID.randomUUID().toString(), entry.actorId(),
                entry.roleContext(), entry.action(), entry.resourceType(),
                entry.resourceId(), null, null, entry.reason(), entry.traceId(),
                eventAt, digest, now, now, entry.actorId(), entry.actorId(), 1L));
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
