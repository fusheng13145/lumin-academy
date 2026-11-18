package com.leyon.lumin.governance.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.port.LegalHoldPort;
import com.leyon.lumin.governance.repository.LegalHoldMapper;
import com.leyon.lumin.governance.repository.LegalHoldRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 法律保全应用服务（docs/04 §5.2 legal_hold；03 §8 / T36）：
 * 立/释（ADMIN，basis_ref_cipher 存加密依据引用字节）+ 状态迁移审计。
 * 所有保全读取留痕：本服务的每次查询路径均写审计（03 §8）。
 * USER 保全释放后：注销推进由 identity 的到期扫描自然续跑
 * （HELD 行到期且无 ACTIVE 保全即匿名化——解除后续执行，无跨域直调）。
 */
@Service
public class LegalHoldService implements LegalHoldPort {

    private final LegalHoldMapper legalHoldMapper;
    private final AuditRecorder auditRecorder;
    private final com.leyon.lumin.foundation.crypto.FieldCipher fieldCipher;

    public LegalHoldService(LegalHoldMapper legalHoldMapper, AuditRecorder auditRecorder,
                            com.leyon.lumin.foundation.crypto.FieldCipher fieldCipher) {
        this.legalHoldMapper = legalHoldMapper;
        this.auditRecorder = auditRecorder;
        this.fieldCipher = fieldCipher;
    }

    /** 立保全（ADMIN；USER/WORK/CASE 主体）。 */
    @Transactional
    public String create(String subject, String stubRole, String subjectType, String subjectId,
                         String reason, LocalDateTime now) {
        requireRole(stubRole);
        if (reason == null || reason.isBlank()
                || !List.of("USER", "WORK", "CASE").contains(subjectType)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        // 所有保全读取留痕：立保前查重亦为读取，写入审计
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                "legal_hold.read", "legal_hold", subjectId, "立保前重复保全检查",
                traceId(), now.toInstant(ZoneOffset.UTC)));
        if (!findActiveBySubject(subjectType, subjectId).isEmpty()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        LegalHoldRow row = new LegalHoldRow(UUID.randomUUID().toString(), subjectType,
                subjectId, fieldCipher.encrypt("hold-basis:" + reason,
                "legal_hold.basis_ref_cipher"), "ACTIVE",
                null, subject, reason, null, now, now, subject, subject, 1L);
        legalHoldMapper.insert(row);
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                "legal_hold.created", "legal_hold", row.id(), reason,
                traceId(), now.toInstant(ZoneOffset.UTC)));
        return row.id();
    }

    /** 释保全（ADMIN）；USER 保全释放后由 identity 到期扫描续跑（解除后续执行）。 */
    @Transactional
    public String release(String subject, String stubRole, String holdId, LocalDateTime now) {
        requireRole(stubRole);
        LegalHoldRow row = legalHoldMapper.findByIdForUpdate(holdId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (legalHoldMapper.release(holdId, now, subject, now, row.lockVersion()) == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        auditRecorder.recordTransition(new AuditRecorder.AuditEntry(subject, stubRole,
                "legal_hold.released", "legal_hold", holdId, "保全释放（解除后续执行）",
                traceId(), now.toInstant(ZoneOffset.UTC)),
                java.util.Map.of("holdState", "ACTIVE"),
                java.util.Map.of("holdState", "RELEASED"));
        return "RELEASED";
    }

    /** 所有保全读取留痕（03 §8）：读取路径写审计后返回。 */
    private List<LegalHoldRow> findActiveBySubject(String subjectType, String subjectId) {
        List<LegalHoldRow> rows = legalHoldMapper.findActiveBySubject(subjectType, subjectId);
        if (!rows.isEmpty()) {
            auditRecorder.record(new AuditRecorder.AuditEntry("system", "SYSTEM",
                    "legal_hold.read", "legal_hold", subjectId,
                    "ACTIVE 保全读取留痕（" + rows.size() + " 条）",
                    traceId(), LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC)));
        }
        return rows;
    }

    @Override
    public boolean hasActive(String subjectType, String subjectId) {
        // 端口语义查询（identity 注销推进）：读取留痕
        return !findActiveBySubject(subjectType, subjectId).isEmpty();
    }

    @Override
    public String activeHoldId(String subjectType, String subjectId) {
        List<LegalHoldRow> rows = findActiveBySubject(subjectType, subjectId);
        return rows.isEmpty() ? null : rows.get(0).id();
    }

    private static void requireRole(String stubRole) {
        if (!"ADMIN".equals(stubRole)) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.contract.ApiEnvelope.currentTraceId();
    }
}

