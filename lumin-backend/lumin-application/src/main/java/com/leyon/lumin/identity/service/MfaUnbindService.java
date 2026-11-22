package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.identity.repository.MfaRecoveryCodeMapper;
import com.leyon.lumin.identity.repository.MfaTotpMapper;
import com.leyon.lumin.identity.repository.MfaUnbindRequestMapper;
import com.leyon.lumin.identity.repository.MfaUnbindRequestRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 二次验证运维解绑（V23；2026-11-20 安全审查 05 项收尾）。
 *
 * <p>背景：设备丢失 / 换机后无法出示验证码，而 {@code ACTIVE} 状态**刻意**不允许自行
 * 重登记（防会话劫持者换掉种子）。此前没有解绑出口，等于用户永久失去账号。
 *
 * <p>链路：申请人登录后提工单（可用恢复码过二次验证挑战）→ OPERATOR/ADMIN 核验身份后
 * 批准 → 种子 {@code ACTIVE→REVOKED}、恢复码全部作废、该主体全部会话撤销 → 本人重新登记。
 *
 * <p>安全口径：
 * <ul>
 *   <li>**没有**"用户自助解绑"接口：那等于把移除第二因子的权力交给会话劫持者；</li>
 *   <li>工单同一主体只能有一张 PENDING（库内生成列唯一约束）；</li>
 *   <li>裁决需要 OPERATOR/ADMIN，且每次裁决写审计（before/after 只含状态字段）；</li>
 *   <li>批准即撤销全部会话——包括提出工单的那一枚，避免旧令牌继续存活。</li>
 * </ul>
 */
@Service
public class MfaUnbindService {

    private static final Logger log = LoggerFactory.getLogger(MfaUnbindService.class);

    /** 可裁决角色（与 role_grant 的角色名一致）。 */
    private static final Set<String> REVIEWER_ROLES = Set.of("OPERATOR", "ADMIN");
    /** 工单列表上限。 */
    private static final int LIST_LIMIT = 100;
    /** 理由长度上限。 */
    private static final int REASON_MAX = 500;

    private final MfaUnbindRequestMapper mapper;
    private final MfaTotpMapper totpMapper;
    private final MfaRecoveryCodeMapper recoveryMapper;
    private final MfaTotpService mfaService;
    private final AuthSessionService sessionService;
    private final AuditRecorder auditRecorder;

    public MfaUnbindService(MfaUnbindRequestMapper mapper, MfaTotpMapper totpMapper,
                            MfaRecoveryCodeMapper recoveryMapper, MfaTotpService mfaService,
                            AuthSessionService sessionService, AuditRecorder auditRecorder) {
        this.mapper = mapper;
        this.totpMapper = totpMapper;
        this.recoveryMapper = recoveryMapper;
        this.mfaService = mfaService;
        this.sessionService = sessionService;
        this.auditRecorder = auditRecorder;
    }

    /** 工单视图（不返回任何种子/恢复码字段）。 */
    public record UnbindView(String id, String subject, String reason, String state,
                             String requestedAt, String reviewedAt) {
    }

    /** 裁决回执（批准时同时告知已撤销的会话数，便于申请人理解"需重新登录"）。 */
    public record Decision(String id, String state, int revokedSessions) {
    }

    /** 提交解绑工单（必须已启用二次验证；同一主体只能一张 PENDING）。 */
    @Transactional
    public UnbindView request(String subject, String reason, LocalDateTime now) {
        if (!mfaService.isActive(subject)) {
            // 没有第二因子可解绑（未登记/已解绑）：409，不创建空工单
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.length() > REASON_MAX) {
            normalizedReason = normalizedReason.substring(0, REASON_MAX);
        }
        if (normalizedReason.isEmpty()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        if (mapper.findPendingBySubject(subject) != null) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        MfaUnbindRequestRow row = new MfaUnbindRequestRow(UUID.randomUUID().toString(), subject,
                normalizedReason, "PENDING", now, null, null, now, now, subject, subject, 1L);
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发同主体重复提交：生成列唯一约束兜底
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        log.info("二次验证解绑申请 event=mfa.unbind_request result=created subject={} id={}",
                subject, row.id());
        return view(row);
    }

    /** 待处理工单（运营/管理员）。 */
    public List<UnbindView> listPending(String role) {
        requireReviewer(role);
        return mapper.listPending(LIST_LIMIT).stream()
                .map(MfaUnbindService::view).toList();
    }

    /** 裁决：批准 → 种子 REVOKED + 恢复码清空 + 全量撤销会话；驳回 → 仅改工单状态。 */
    @Transactional
    public Decision decide(String reviewer, String role, String requestId, boolean approve,
                           LocalDateTime now) {
        requireReviewer(role);
        MfaUnbindRequestRow row = requestId == null ? null : mapper.findById(requestId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (!"PENDING".equals(row.state())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        String target = approve ? "APPROVED" : "REJECTED";
        if (mapper.decide(row.id(), target, reviewer, now, reviewer, row.lockVersion()) == 0) {
            // 并发：已被其他运营处理
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        int revokedSessions = 0;
        if (approve) {
            int revokedCredential = totpMapper.revoke(row.subject(), reviewer, now);
            recoveryMapper.deleteAllFor(row.subject());
            revokedSessions = sessionService.revokeAllFor(row.subject());
            auditRecorder.recordTransition(
                    new AuditRecorder.AuditEntry(reviewer, role, "mfa.unbind.approved",
                            "mfa_unbind_request", row.id(), row.reason(),
                            com.leyon.lumin.foundation.contract.ApiEnvelope.currentTraceId(),
                            now.toInstant(ZoneOffset.UTC)),
                    Map.of("mfaState", "ACTIVE"),
                    Map.of("mfaState", "REVOKED"));
            // 高危：解除第二因子属可被社工利用的操作，固定 WARN 级
            log.warn("二次验证解绑生效 event=mfa.unbind result=approved subject={} requestId={} "
                    + "credentialRevoked={} sessionsRevoked={}", row.subject(), row.id(),
                    revokedCredential, revokedSessions);
        } else {
            log.info("二次验证解绑被驳回 event=mfa.unbind result=rejected subject={} requestId={}",
                    row.subject(), row.id());
        }
        return new Decision(row.id(), target, revokedSessions);
    }

    private static void requireReviewer(String role) {
        if (role == null || !REVIEWER_ROLES.contains(role)) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
    }

    private static UnbindView view(MfaUnbindRequestRow row) {
        return new UnbindView(row.id(), row.subject(), row.reason(), row.state(),
                row.requestedAt().toInstant(ZoneOffset.UTC).toString(),
                row.reviewedAt() == null ? null
                        : row.reviewedAt().toInstant(ZoneOffset.UTC).toString());
    }
}
