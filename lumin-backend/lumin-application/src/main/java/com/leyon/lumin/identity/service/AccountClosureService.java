package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.port.LegalHoldPort;
import com.leyon.lumin.identity.repository.AccountClosureMapper;
import com.leyon.lumin.identity.repository.AccountClosureRow;
import com.leyon.lumin.identity.repository.UserProfileMapper;
import com.leyon.lumin.identity.repository.UserProfileRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 注销与匿名化应用服务（03 §8 Tier0 数据留存 / T36）：
 * 注销**立即断开公开关联**（account_status=CLOSED、公开昵称匿名、closed_at 留痕；
 * 会话撤销随 OIDC 接入落地——登记）并建立推进状态行（匿名化到期 = +6 个月）；
 * 有 ACTIVE 法律保全的账号进入 HELD（保全转 HELD 并限制访问，不无限静默保留），
 * 保全解除后由 SYSTEM 推进内部匿名化（到期才推进；审计事实不删）。
 * 保全查询经 foundation 端口（identity→governance 非允许边）。
 */
@Service
public class AccountClosureService {

    /** 内部关联匿名化等待期（03 §8：6 个月，工程执行候选）。 */
    private static final java.time.Period ANONYMIZE_DELAY = java.time.Period.ofMonths(6);
    private static final String PUBLIC_ANONYMOUS_NICKNAME = "已注销用户";
    private static final String ANONYMIZED_ISSUER = "urn:lumin:anonymized";

    private final UserProfileMapper profileMapper;
    private final AccountClosureMapper closureMapper;
    private final LegalHoldPort legalHoldPort;
    private final AuditRecorder auditRecorder;
    private final AuthSessionService authSessionService;

    public AccountClosureService(UserProfileMapper profileMapper,
                                 AccountClosureMapper closureMapper,
                                 LegalHoldPort legalHoldPort,
                                 AuditRecorder auditRecorder,
                                 AuthSessionService authSessionService) {
        this.profileMapper = profileMapper;
        this.closureMapper = closureMapper;
        this.legalHoldPort = legalHoldPort;
        this.auditRecorder = auditRecorder;
        this.authSessionService = authSessionService;
    }

    /** 注销申请（本人）：立即公开匿名 + 建立推进行；有 ACTIVE 保全则 HELD。 */
    @Transactional
    public AccountClosureRow requestClosure(String subject, LocalDateTime now) {
        UserProfileRow profile = profileMapper.findById(subject);
        if (profile == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if ("CLOSED".equals(profile.accountStatus())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        // 立即公开匿名：昵称匿名 + CLOSED + closed_at（内部关联 6 个月后匿名化）
        profileMapper.applyClosure(subject, PUBLIC_ANONYMOUS_NICKNAME, now, subject);
        // 撤销会话（T36：注销撤销会话；UGC-002 会话层联动）
        int revokedSessions = authSessionService.revokeAllFor(subject);
        boolean held = legalHoldPort.hasActive("USER", subject);
        String holdId = held ? legalHoldPort.activeHoldId("USER", subject) : null;
        AccountClosureRow row = new AccountClosureRow(UUID.randomUUID().toString(),
                subject, held ? "HELD" : "PENDING", now, now.plus(ANONYMIZE_DELAY), holdId,
                now, now, subject, subject, 1L);
        closureMapper.insert(row);
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, "AUTHOR",
                "account.closed", "user_profile", subject,
                (held ? "注销（法律保全 HELD，内部匿名化暂缓）" : "注销（公开关联立即断开）")
                        + "，撤销会话 " + revokedSessions,
                traceId(), now.toInstant(ZoneOffset.UTC)));
        return row;
    }

    /** SYSTEM 推进：到期且无 ACTIVE 保全 → 匿名化内部关联（审计事实不删）。 */
    @Transactional
    public int promoteDue(LocalDateTime now) {
        List<AccountClosureRow> due = closureMapper.listDue(now);
        int promoted = 0;
        for (AccountClosureRow row : due) {
            if (legalHoldPort.hasActive("USER", row.userId())) {
                continue;
            }
            if (closureMapper.transition(row.id(), "ANONYMIZED", null, row.state(),
                    row.lockVersion(), "system", now) == 0) {
                continue;
            }
            profileMapper.anonymizeInternal(row.userId(), ANONYMIZED_ISSUER,
                    "anon:" + row.userId(), "system", now);
            auditRecorder.record(new AuditRecorder.AuditEntry("system", "SYSTEM",
                    "account.anonymized", "user_profile", row.userId(),
                    "注销满 6 个月：内部关联匿名化（审计事实不删）",
                    traceId(), now.toInstant(ZoneOffset.UTC)));
            promoted++;
        }
        return promoted;
    }

    /** 查询本人注销推进状态。 */
    @Transactional(readOnly = true)
    public AccountClosureRow get(String subject) {
        AccountClosureRow row = closureMapper.findByUser(subject);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return row;
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.contract.ApiEnvelope.currentTraceId();
    }
}

