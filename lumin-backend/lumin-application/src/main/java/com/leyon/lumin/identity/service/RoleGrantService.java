package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.values.RoleCode;
import com.leyon.lumin.identity.domain.RolePolicy;
import com.leyon.lumin.identity.repository.RoleAssignmentMapper;
import com.leyon.lumin.identity.repository.RoleGrantMapper;
import com.leyon.lumin.identity.repository.RoleGrantRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 角色授予链应用服务（docs/04 §5.2 role_grant；docs/13 UGC-004；6.8 互斥）：
 * 提案（OPERATOR/ADMIN）→ 独立 ADMIN 审批（requested_by≠reviewed_by；
 * RolePolicy.canAssign 互斥校验——审核员禁止兼任作者、运营与审核严格分离）→
 * APPROVED 即建 role_assignment 绑定（grant_id 外键，作用域/到期随授予）。
 * 到期授予不再生效（查询口径过滤）；审计事实不删。
 */
@Service
public class RoleGrantService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final Set<String> PROPOSER_ROLES = Set.of("OPERATOR", ROLE_ADMIN);

    private final RoleGrantMapper grantMapper;
    private final RoleAssignmentMapper assignmentMapper;
    private final AuditRecorder auditRecorder;

    public RoleGrantService(RoleGrantMapper grantMapper,
                            RoleAssignmentMapper assignmentMapper,
                            AuditRecorder auditRecorder) {
        this.grantMapper = grantMapper;
        this.assignmentMapper = assignmentMapper;
        this.auditRecorder = auditRecorder;
    }

    /** 提案（OPERATOR/ADMIN；互斥在审批时校验——03 §2.4「审批时再次执行互斥校验」）。 */
    @Transactional
    public String propose(String subject, String stubRole, String targetUserId, String role,
                          String scopeType, String scopeId, LocalDateTime expiresAt,
                          String reason, LocalDateTime now) {
        if (!PROPOSER_ROLES.contains(stubRole)) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
        if (reason == null || reason.isBlank() || targetUserId == null
                || RoleCode.valueOfOrNull(role) == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        RoleGrantRow row = new RoleGrantRow(UUID.randomUUID().toString(), targetUserId,
                role, scopeType == null ? "GLOBAL" : scopeType, scopeId, expiresAt,
                reason, "PENDING", subject, null, null, now, now, subject, subject, 1L);
        grantMapper.insert(row);
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                "role_grant.proposed", "role_grant", row.id(),
                role + " → " + targetUserId + "：" + reason,
                traceId(), now.toInstant(ZoneOffset.UTC)));
        return row.id();
    }

    /** 独立 ADMIN 审批（互斥校验 + APPROVED 即建绑定；到期授予不建有效角色）。 */
    @Transactional
    public String review(String reviewer, String stubRole, String grantId, boolean approve,
                         LocalDateTime now) {
        boolean adminBase = ROLE_ADMIN.equals(stubRole);
        boolean adminGranted = currentGrantedRoles(reviewer).contains(RoleCode.ADMIN);
        if (!adminBase && !adminGranted) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
        RoleGrantRow row = grantMapper.findByIdForUpdate(grantId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (reviewer.equals(row.requestedBy())) {
            // 05 §4.2 / 6.8：提案人与审批人分离
            throw new LuminBusinessException(ErrorCode.A2001);
        }
        RoleCode candidate = RoleCode.valueOfOrNull(row.role());
        if (candidate == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        if (approve && !RolePolicy.canAssign(currentGrantedRoles(row.targetUserId()),
                candidate)) {
            // 6.8 互斥：审核员禁止兼任作者、运营与审核严格分离
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String targetState = approve ? "APPROVED" : "REJECTED";
        if (grantMapper.review(grantId, targetState, reviewer, now, reviewer, now,
                row.lockVersion()) == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        if (approve) {
            // 绑定 role_assignment（grant_id 外键；到期授予仅登记历史，不产生有效角色）
            assignmentMapper.insertActive(UUID.randomUUID().toString(),
                    row.targetUserId(), row.role(),
                    row.scopeType() == null ? "GLOBAL" : row.scopeType(),
                    row.scopeId(), row.expiresAt(), row.id(), now, reviewer);
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(reviewer, ROLE_ADMIN,
                "role_grant." + (approve ? "approved" : "rejected"),
                "role_grant", grantId, row.reason() + "（"
                        + candidate.name() + " → " + row.targetUserId() + "）",
                traceId(), now.toInstant(ZoneOffset.UTC)));
        return targetState;
    }

    /** 目标主体当前已授予角色（APPROVED 且未过期——到期授予不参与互斥与授权）。 */
    private Set<RoleCode> currentGrantedRoles(String userId) {
        List<RoleGrantRow> active = grantMapper.findActiveBySubject(userId,
                LocalDateTime.now(ZoneOffset.UTC));
        return active.stream()
                .map(g -> RoleCode.valueOfOrNull(g.role()))
                .filter(r -> r != null)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.contract.ApiEnvelope.currentTraceId();
    }
}
