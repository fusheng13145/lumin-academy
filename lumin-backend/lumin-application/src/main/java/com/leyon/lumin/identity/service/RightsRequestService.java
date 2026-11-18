package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.crypto.FieldCipher;
import com.leyon.lumin.foundation.port.LegalHoldPort;
import com.leyon.lumin.identity.repository.RightsRequestMapper;
import com.leyon.lumin.identity.repository.RightsRequestRow;
import com.leyon.lumin.identity.repository.UserProfileMapper;
import com.leyon.lumin.identity.repository.UserProfileRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 数据主体权利请求应用服务（docs/04 §5.2 rights_request；05 §3.4；6.9；T36 续）：
 * 四类自助闭环——ACCESS（本人资料快照）/ EXPORT（引用加密留痕、到期 24 小时，
 * 下载二次认证随 UGC-002 后续）/ CORRECT（更正公开昵称）/ ERASE（匿名化内部
 * 关联，同注销推进口径）。法律保全：ACTIVE 保全 → HELD（仅保全阻止匿名化，
 * 解除后经 retryHeld 续跑）。审计事实不删；非本人请求不可见。
 */
@Service
public class RightsRequestService {

    /** 导出引用有效期（04 §5.2：导出有效期 24 小时；下载二次认证随后续批次）。 */
    private static final java.time.Duration EXPORT_TTL = java.time.Duration.ofHours(24);
    /** 非导出类的处理时限（03 §8 工程候选：30 日）。 */
    private static final java.time.Period DEFAULT_SLA = java.time.Period.ofDays(30);

    private final RightsRequestMapper requestMapper;
    private final UserProfileMapper profileMapper;
    private final LegalHoldPort legalHoldPort;
    private final com.leyon.lumin.foundation.crypto.FieldCipher fieldCipher;
    private final AuditRecorder auditRecorder;

    public RightsRequestService(RightsRequestMapper requestMapper,
                                UserProfileMapper profileMapper,
                                LegalHoldPort legalHoldPort,
                                com.leyon.lumin.foundation.crypto.FieldCipher fieldCipher,
                                AuditRecorder auditRecorder) {
        this.requestMapper = requestMapper;
        this.profileMapper = profileMapper;
        this.legalHoldPort = legalHoldPort;
        this.fieldCipher = fieldCipher;
        this.auditRecorder = auditRecorder;
    }

    /** 提交并即时自助处理（ERASE 受法律保全闸门：ACTIVE 保全 → HELD）。 */
    @Transactional
    public RightsRequestRow create(String subject, String kind, String reason,
                                   String correctionNickname, LocalDateTime now) {
        if (reason == null || reason.isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        UserProfileRow profile = profileMapper.findById(subject);
        if (profile == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        LocalDateTime due = now.plus(kind.equals("EXPORT") ? EXPORT_TTL : DEFAULT_SLA);
        String state = "PROCESSING";
        String holdId = null;
        byte[] resultRef = null;
        if ("ERASE".equals(kind) && legalHoldPort.hasActive("USER", subject)) {
            // 03 §2.4：法律保全阻止匿名化（HELD 可观察；解除后续跑）
            state = "HELD";
            holdId = legalHoldPort.activeHoldId("USER", subject);
        }
        RightsRequestRow row = new RightsRequestRow(UUID.randomUUID().toString(), subject,
                kind, state, reason, due, holdId, null, now, now, subject, subject, 1L);
        requestMapper.insert(row);
        if ("HELD".equals(state)) {
            auditRecorder.record(new AuditRecorder.AuditEntry(subject, "AUTHOR",
                    "rights_request.held", "rights_request", row.id(),
                    "ERASE 受法律保全暂缓（解除后续跑）",
                    traceId(), now.toInstant(ZoneOffset.UTC)));
            return row;
        }
        return process(row, correctionNickname, now);
    }

    /** 自助处理（即时闭环；ERASE 匿名化内部关联，同注销推进口径）。 */
    private RightsRequestRow process(RightsRequestRow row, String correctionNickname,
                                     LocalDateTime now) {
        String subject = row.userId();
        byte[] resultRef = null;
        switch (row.kind()) {
            case "ACCESS" -> resultRef = fieldCipher.encrypt(profileSnapshot(subject),
                    "rights_request.result_ref");
            case "EXPORT" -> resultRef = fieldCipher.encrypt(exportToken(row.id(), now),
                    "rights_request.result_ref");
            case "CORRECT" -> {
                if (correctionNickname == null || correctionNickname.isBlank()) {
                    throw new LuminBusinessException(ErrorCode.P1001);
                }
                profileMapper.applyCorrection(subject, correctionNickname, subject, now);
            }
            case "ERASE" -> profileMapper.anonymizeInternal(subject,
                    "urn:lumin:anonymized", "anon:" + subject, "system", now);
            default -> throw new LuminBusinessException(ErrorCode.P1001);
        }
        if (requestMapper.transition(row.id(), "COMPLETED", row.holdId(), resultRef,
                row.state(), row.lockVersion(), subject, now) == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, "AUTHOR",
                "rights_request." + row.kind().toLowerCase() + ".completed",
                "rights_request", row.id(), "自助闭环完成", traceId(),
                now.toInstant(ZoneOffset.UTC)));
        return requestMapper.findById(row.id());
    }

    /** HELD 请求续跑（保全解除后由调用方触发；成功 → COMPLETED + 匿名化）。 */
    @Transactional
    public int retryHeld(LocalDateTime now) {
        List<RightsRequestRow> held = requestMapper.listHeldErase();
        int completed = 0;
        for (RightsRequestRow row : held) {
            if (legalHoldPort.hasActive("USER", row.userId())) {
                continue;
            }
            profileMapper.anonymizeInternal(row.userId(), "urn:lumin:anonymized",
                    "anon:" + row.userId(), "system", now);
            if (requestMapper.transition(row.id(), "COMPLETED", row.holdId(), null,
                    "HELD", row.lockVersion(), "system", now) > 0) {
                auditRecorder.record(new AuditRecorder.AuditEntry("system", "SYSTEM",
                        "rights_request.erase.completed", "rights_request", row.id(),
                        "保全解除后匿名化续跑完成", traceId(), now.toInstant(ZoneOffset.UTC)));
                completed++;
            }
        }
        return completed;
    }

    /** 本人列表（仅创建者可见）。 */
    @Transactional(readOnly = true)
    public List<RightsRequestRow> listMine(String subject) {
        return requestMapper.listByUser(subject, 100);
    }

    /** 本人单条（他人 → 404，越权隔离）。 */
    @Transactional(readOnly = true)
    public RightsRequestRow getMine(String subject, String id) {
        RightsRequestRow row = requestMapper.findById(id);
        if (row == null || !row.userId().equals(subject)) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return row;
    }

    /** 本人资料快照（ACCESS：最少必要字段，非全文导出）。 */
    private String profileSnapshot(String subject) {
        UserProfileRow p = profileMapper.findById(subject);
        return "nickname=" + (p == null ? "" : p.nickname())
                + ";status=" + (p == null ? "" : p.accountStatus())
                + ";closed_at=" + (p == null || p.closedAt() == null ? "" : p.closedAt());
    }

    /** 导出引用令牌（引用而非数据本体；24h 失效；下载二次认证随后续批次）。 */
    private String exportToken(String requestId, LocalDateTime now) {
        return "export:" + requestId + ":" + now.plusSeconds(86400).toInstant(ZoneOffset.UTC);
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.contract.ApiEnvelope.currentTraceId();
    }
}
