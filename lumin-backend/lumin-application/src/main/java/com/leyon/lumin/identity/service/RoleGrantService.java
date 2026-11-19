package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import com.leyon.lumin.foundation.values.RoleCode;
import com.leyon.lumin.identity.domain.RolePolicy;
import com.leyon.lumin.identity.repository.RoleAssignmentMapper;
import com.leyon.lumin.identity.repository.RoleGrantMapper;
import com.leyon.lumin.identity.repository.RoleGrantRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 角色授予链应用服务（docs/04 §5.2 role_grant；docs/13 UGC-004；6.8 互斥）：
 * 提案（OPERATOR/ADMIN）→ 独立 ADMIN 审批（requested_by≠reviewed_by；
 * RolePolicy.canAssign 互斥校验——审核员禁止兼任作者、运营与审核严格分离）→
 * APPROVED 即建 role_assignment 绑定（grant_id 外键，作用域/到期随授予）。
 * 到期授予不再生效（查询口径过滤）；审批历史追加到 proposal_review，不覆写。
 */
@Service
public class RoleGrantService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final Set<String> PROPOSER_ROLES = Set.of("OPERATOR", ROLE_ADMIN);
    private static final int PROPOSE_REASON_MAX = 500;
    private static final int REVIEW_EVIDENCE_MAX = 10;
    private static final int REVIEW_REASON_MAX = 1000;

    private final RoleGrantMapper grantMapper;
    private final ProposalReviewStore reviewStore;
    private final RoleAssignmentMapper assignmentMapper;
    private final AuditRecorder auditRecorder;
    private final String cursorKey;

    public RoleGrantService(RoleGrantMapper grantMapper,
                            ProposalReviewStore reviewStore,
                            RoleAssignmentMapper assignmentMapper,
                            AuditRecorder auditRecorder,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${lumin.cursor.key}") String cursorKey) {
        this.grantMapper = grantMapper;
        this.reviewStore = reviewStore;
        this.assignmentMapper = assignmentMapper;
        this.auditRecorder = auditRecorder;
        this.cursorKey = cursorKey;
    }

    /** 契约 RoleGrantRequest（05 §3.2）。 */
    public record RoleGrantRequestView(String targetUserId, String role, String scopeType,
                                       String scopeId, String expiresAt, String reason) {
    }

    /** 契约 ReviewRecord（服务端追加的完整审批记录）。 */
    public record ReviewView(String id, String actorId, String action, String reason,
                             List<String> evidenceRefs, long reviewedRevision,
                             String createdAt) {
    }

    /** 契约 RoleGrantView。 */
    public record RoleGrantView(String id, String createdBy, String createdAt,
                                RoleGrantRequestView request, long revision,
                                String state, List<ReviewView> reviews) {
    }

    /** 契约 RoleGrantPage。 */
    public record Page(List<RoleGrantView> items, String nextCursor) {
    }

    /** 提案（OPERATOR/ADMIN；互斥在审批时校验——03 §2.4「审批时再次执行互斥校验」）。 */
    @Transactional
    public String propose(String subject, String stubRole, String targetUserId, String role,
                          String scopeType, String scopeId, LocalDateTime expiresAt,
                          String reason, LocalDateTime now) {
        if (!PROPOSER_ROLES.contains(stubRole)) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
        if (reason == null || reason.isBlank() || reason.length() > PROPOSE_REASON_MAX
                || targetUserId == null || RoleCode.valueOfOrNull(role) == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String normalizedScope = scopeType == null || scopeType.isBlank()
                ? "GLOBAL" : scopeType;
        if (!Set.of("GLOBAL", "WORK", "QUEUE", "CATEGORY").contains(normalizedScope)
                || ("GLOBAL".equals(normalizedScope) && scopeId != null && !scopeId.isBlank())
                || (!"GLOBAL".equals(normalizedScope)
                    && (scopeId == null || scopeId.isBlank()))) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        RoleGrantRow row = new RoleGrantRow(UUID.randomUUID().toString(), targetUserId,
                role, normalizedScope, scopeId, expiresAt,
                reason, "PENDING", subject, null, null, now, now, subject, subject, 1L);
        grantMapper.insert(row);
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                "role_grant.proposed", "role_grant", row.id(),
                role + " → " + targetUserId + "：" + reason,
                traceId(), now.toInstant(ZoneOffset.UTC)));
        return row.id();
    }

    /** 独立 ADMIN 审批（兼容既有调用：无显式理由/证据时使用稳定默认）。 */
    @Transactional
    public String review(String reviewer, String stubRole, String grantId, boolean approve,
                         LocalDateTime now) {
        return review(reviewer, stubRole, grantId, approve,
                approve ? "审批通过" : "审批驳回",
                List.of("role_grant:" + grantId), now);
    }

    /** 独立 ADMIN 审批（互斥校验 + APPROVED 即建绑定；审批历史追加留痕）。 */
    @Transactional
    public String review(String reviewer, String stubRole, String grantId, boolean approve,
                         String reason, List<String> evidenceRefs, LocalDateTime now) {
        boolean adminBase = ROLE_ADMIN.equals(stubRole);
        boolean adminGranted = currentGrantedRoles(reviewer).contains(RoleCode.ADMIN);
        if (!adminBase && !adminGranted) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
        String normalizedReason = normalizeReviewReason(reason, approve);
        List<String> normalizedEvidence = normalizeEvidenceRefs(evidenceRefs, grantId);
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
        // 契约 reviews：request 域按 ROLE_GRANT 追加记录；reviewedRevision 为被审版本
        reviewStore.append("ROLE_GRANT", grantId, reviewer, approve ? "APPROVE" : "REJECT",
                normalizedReason, normalizedEvidence, row.lockVersion(), now);
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

    /**
     * 可见授予列表（05 §3.2 listRoleGrants）：ADMIN 全量，其他主体仅本人提案或
     * 本人被授予；授权过滤先于分页，游标绑定主体与过滤摘要。
     */
    @Transactional(readOnly = true)
    public Page listVisible(String subject, String stubRole, String cursorToken,
                            Integer limitParam, LocalDateTime now) {
        ErrorCode limitCheck = CursorCodec.validateLimit(limitParam);
        if (limitCheck != ErrorCode.OK) {
            throw new LuminBusinessException(limitCheck);
        }
        int limit = CursorCodec.resolveLimit(limitParam);
        boolean includeAll = ROLE_ADMIN.equals(stubRole)
                || currentGrantedRoles(subject).contains(RoleCode.ADMIN);
        String filterDigest = CursorCodec.filterDigest(
                "list=role-grants;viewer=" + subject + ";all=" + includeAll);
        LocalDateTime cursorAt = null;
        String cursorId = null;
        if (cursorToken != null && !cursorToken.isBlank()) {
            CursorCodec.DecodedCursor decoded = CursorCodec.decode(cursorToken,
                    cursorKey.getBytes(StandardCharsets.UTF_8),
                    now.toInstant(ZoneOffset.UTC), filterDigest);
            if (decoded.code() != ErrorCode.OK) {
                throw new LuminBusinessException(decoded.code());
            }
            if (!subject.equals(decoded.payload().subjectId().toString())) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
            cursorAt = LocalDateTime.parse(decoded.payload().sortKeyValue());
            cursorId = decoded.payload().lastId().toString();
        }
        List<RoleGrantRow> rows = grantMapper.listVisible(subject, includeAll,
                cursorAt, cursorId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<RoleGrantRow> page = rows.subList(0, Math.min(rows.size(), limit));
        List<RoleGrantView> items = new ArrayList<>(page.size());
        for (RoleGrantRow row : page) {
            items.add(toView(row, now));
        }
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            RoleGrantRow last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(new CursorCodec.Payload(
                    last.createdAt().toString(), UUID.fromString(last.id()),
                    filterDigest, UUID.fromString(subject),
                    now.toInstant(ZoneOffset.UTC)),
                    cursorKey.getBytes(StandardCharsets.UTF_8));
        }
        return new Page(items, nextCursor);
    }

    /** 详情（05 §3.2 getRoleGrant）：与列表同一主体检查；不存在/越权统一 404。 */
    @Transactional(readOnly = true)
    public RoleGrantView getVisible(String subject, String stubRole, String grantId,
                                    LocalDateTime now) {
        RoleGrantRow row = grantMapper.findById(grantId);
        boolean includeAll = ROLE_ADMIN.equals(stubRole)
                || currentGrantedRoles(subject).contains(RoleCode.ADMIN);
        if (row == null || (!includeAll && !subject.equals(row.requestedBy())
                && !subject.equals(row.targetUserId()))) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return toView(row, now);
    }

    private RoleGrantView toView(RoleGrantRow row, LocalDateTime now) {
        String state = row.state();
        if ("APPROVED".equals(state) && row.expiresAt() != null
                && !row.expiresAt().isAfter(now)) {
            state = "EXPIRED";
        }
        List<ReviewView> reviews = reviewStore.listByResource("ROLE_GRANT", row.id())
                .stream()
                .map(r -> new ReviewView(r.id(), r.actorId(), r.action(), r.reason(),
                        r.evidenceRefs(), r.reviewedRevision(),
                        r.eventAt().toString()))
                .toList();
        return new RoleGrantView(row.id(), row.requestedBy(), row.createdAt().toString(),
                new RoleGrantRequestView(row.targetUserId(), row.role(), row.scopeType(),
                        row.scopeId(), row.expiresAt() == null ? null
                        : row.expiresAt().toString(), row.reason()),
                row.lockVersion() == null ? 1L : row.lockVersion(), state, reviews);
    }

    private List<String> normalizeEvidenceRefs(List<String> evidenceRefs, String grantId) {
        List<String> refs = evidenceRefs == null || evidenceRefs.isEmpty()
                ? List.of("role_grant:" + grantId) : evidenceRefs;
        if (refs.size() > REVIEW_EVIDENCE_MAX) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        for (String ref : refs) {
            if (ref == null || ref.isBlank() || ref.length() > 200
                    || !ref.matches("[A-Za-z0-9:_-]+")) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
        }
        return refs;
    }

    private String normalizeReviewReason(String reason, boolean approve) {
        String value = reason == null || reason.isBlank()
                ? (approve ? "审批通过" : "审批驳回") : reason.trim();
        if (value.length() > REVIEW_REASON_MAX) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return value;
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
