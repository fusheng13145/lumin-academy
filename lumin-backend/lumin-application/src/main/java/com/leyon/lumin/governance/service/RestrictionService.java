package com.leyon.lumin.governance.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.governance.domain.RestrictionStateMachine;
import com.leyon.lumin.governance.domain.RestrictionStateMachine.TransitionResult;
import com.leyon.lumin.foundation.values.RestrictionKind;
import com.leyon.lumin.foundation.values.RestrictionState;
import com.leyon.lumin.foundation.web.ReceiptView;
import com.leyon.lumin.governance.repository.RestrictionMapper;
import com.leyon.lumin.governance.repository.RestrictionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 治理措施应用服务（03 §2.4 restriction / §6；T24–T26）：
 * 提案（PENDING）→ 独立复核生效（ACTIVE，立即参与可见性裁决）/ 驳回（REJECTED）
 * → 临时措施改判解除（ACTIVE→REVOKED，仅 TEMPORARY/GUARDIAN_REVOKED）。
 * 终局违规与合同终止不可改判恢复传播（03 §6；状态机兜底）。
 * 生效限制经 foundation 端口供读者裁决读取（INV-03：生效限制优先于发布指针）。
 */
@Service
public class RestrictionService {

    private final RestrictionMapper restrictionMapper;
    private final AuditRecorder auditRecorder;
    private final ProposalReviewStore reviewStore;
    private final com.leyon.lumin.foundation.port.PublicationGatePort publicationGatePort;

    public RestrictionService(RestrictionMapper restrictionMapper,
                              AuditRecorder auditRecorder,
                              ProposalReviewStore reviewStore,
                              com.leyon.lumin.foundation.port.PublicationGatePort publicationGatePort) {
        this.restrictionMapper = restrictionMapper;
        this.auditRecorder = auditRecorder;
        this.reviewStore = reviewStore;
        this.publicationGatePort = publicationGatePort;
    }

    /** 提案（PENDING）：05 §3.3 requestRestriction——REVIEWER_L2/LEGAL/OPERATOR 提案。 */
    @Transactional
    public ReceiptView requestRestriction(String requester, String workId, String chapterId,
                                          String kind, String reason, LocalDateTime expiresAt) {
        return requestRestriction(requester, workId, chapterId, kind, reason, expiresAt, null);
    }

    /** 契约 requestRestriction：caseId 绑定原案件；缺失时保留骨架合成值。 */
    @Transactional
    public ReceiptView requestRestriction(String requester, String workId, String chapterId,
                                          String kind, String reason, LocalDateTime expiresAt,
                                          String caseId) {
        if (reason == null || reason.isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RestrictionKind restrictionKind;
        try {
            restrictionKind = RestrictionKind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        // 03 §2.4：临时措施必有 expires_at；终局/解约禁止（不可伪装自动到期恢复）
        if (restrictionKind == RestrictionKind.TEMPORARY && expiresAt == null) {
            // 骨架默认 30 天（到期进入复核告警，不自动公开——03 §2.4）
            expiresAt = now.plusDays(30);
        }
        if (restrictionKind != RestrictionKind.TEMPORARY && expiresAt != null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        RestrictionRow row = new RestrictionRow(UUID.randomUUID().toString(), workId,
                chapterId, kind, "PENDING", reason, caseId == null
                        ? UUID.randomUUID().toString() : caseId,
                requester, null, null, expiresAt, null, null, now, now, requester,
                requester, 1L);
        restrictionMapper.insert(row);
        auditRecorder.record(new AuditRecorder.AuditEntry(requester,
                requesterRole(requester), "restriction.requested", "restriction",
                row.id(), reason, traceId(), now.toInstant(ZoneOffset.UTC)));
        return new ReceiptView(row.id(), "RESTRICTION", "PENDING", 1L);
    }

    /** 独立复核生效（PENDING→ACTIVE；提案人不得自批由服务层校验）。 */
    @Transactional
    public ReceiptView reviewRestriction(String reviewer, String restrictionId, boolean activate,
                                         String ifMatchExpectedLock) {
        return reviewRestriction(reviewer, restrictionId, activate, null, null,
                ifMatchExpectedLock);
    }

    /** 契约 reviewRestriction：审批理由与证据追加到 proposal_review，不覆写历史。 */
    @Transactional
    public ReceiptView reviewRestriction(String reviewer, String restrictionId, boolean activate,
                                         String reason, java.util.List<String> evidenceRefs,
                                         String ifMatchExpectedLock) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RestrictionRow row = restrictionId == null ? null
                : restrictionMapper.findById(restrictionId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (row.requestedBy().equals(reviewer)) {
            // 03 §2.4/§6：提案人与复核人不同（防自我裁决）
            throw new LuminBusinessException(ErrorCode.A2001);
        }
        String normalizedReason = normalizeReason(reason,
                activate ? "独立复核生效" : "独立复核驳回");
        long expectedLock = com.leyon.lumin.foundation.web.IfMatch.requireValid(ifMatchExpectedLock);
        if (expectedLock != row.lockVersion()) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        TransitionResult transition = RestrictionStateMachine.transit(
                RestrictionState.valueOf(row.state()),
                activate ? com.leyon.lumin.foundation.values.RestrictionEvent.ACTIVATE
                        : com.leyon.lumin.foundation.values.RestrictionEvent.REJECT,
                RestrictionKind.valueOf(row.kind()));
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        int rows = restrictionMapper.transitionState(restrictionId,
                transition.targetState().name(), reviewer, null, row.state(), "system",
                now, row.lockVersion());
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        reviewStore.append("RESTRICTION", restrictionId, reviewer,
                activate ? "APPROVE" : "REJECT", normalizedReason,
                evidenceList(evidenceRefs, restrictionId), row.lockVersion(), now);
        if (activate) {
            // 03 §2.1 生效下架：SYSTEM 执行已核验治理措施（业务态 → REMOVED，保存前态）
            publicationGatePort.takedown(targetScope(row), row.workId(), row.chapterId());
        }
        auditRecorder.recordTransition(new AuditRecorder.AuditEntry(reviewer, "REVIEWER_L2",
                "restriction." + (activate ? "activated" : "rejected"),
                "restriction", restrictionId, normalizedReason,
                traceId(), now.toInstant(ZoneOffset.UTC)),
                java.util.Map.of("restrictionState", row.state()),
                java.util.Map.of("restrictionState", transition.targetState().name()));
        return new ReceiptView(restrictionId, "RESTRICTION",
                transition.targetState().name(), row.lockVersion() + 1);
    }

    /** 临时措施改判解除（ACTIVE→REVOKED；终局/解约被状态机拒绝）。 */
    @Transactional
    public ReceiptView revokeRestriction(String reviewer, String restrictionId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RestrictionRow row = restrictionMapper.findById(restrictionId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        TransitionResult transition = RestrictionStateMachine.transit(
                RestrictionState.ACTIVE,
                com.leyon.lumin.foundation.values.RestrictionEvent.REVOKE,
                RestrictionKind.valueOf(row.kind()));
        if (!transition.accepted()) {
            // 终局违规/合同终止：不可恢复传播（B2005 服务层口径）
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        int rows = restrictionMapper.transitionState(restrictionId, "REVOKED", reviewer,
                now, "ACTIVE", "system", now, row.lockVersion());
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        reviewStore.append("RESTRICTION", restrictionId, reviewer, "REVOKE",
                "临时措施独立复核改判解除", evidenceList(null, restrictionId),
                row.lockVersion(), now);
        // 改判恢复：仅当所有生效限制均解除且指针在时恢复记录的前态（03 §2.1）
        publicationGatePort.restore(targetScope(row), row.workId(), row.chapterId());
        auditRecorder.recordTransition(new AuditRecorder.AuditEntry(reviewer, "REVIEWER_L2",
                "restriction.revoked", "restriction", restrictionId,
                "临时措施独立复核改判解除", traceId(), now.toInstant(ZoneOffset.UTC)),
                java.util.Map.of("restrictionState", "ACTIVE"),
                java.util.Map.of("restrictionState", "REVOKED"));
        return new ReceiptView(restrictionId, "RESTRICTION", "REVOKED", row.lockVersion() + 1);
    }

    /** 限制作用域：章节级限制 chapterId 非空，否则为作品级。 */
    private static String targetScope(RestrictionRow row) {
        return row.chapterId() == null ? "WORK" : "CHAPTER";
    }

    private String requesterRole(String requester) {
        // 骨架口径：替身身份角色由接入层判定，审计 role_context 以提案行为记录
        return "OPERATOR";
    }

    private static String normalizeReason(String reason, String fallback) {
        String value = reason == null || reason.isBlank() ? fallback : reason.trim();
        if (value.length() > 1000) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return value;
    }

    /** 证据为空时绑定资源本身；契约要求 evidenceRefs 至少 1 项、字符集受限。 */
    private static java.util.List<String> evidenceList(java.util.List<String> evidenceRefs,
                                                       String restrictionId) {
        return evidenceRefs == null || evidenceRefs.isEmpty()
                ? java.util.List.of("restriction:" + restrictionId)
                : evidenceRefs;
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.contract.ApiEnvelope.currentTraceId();
    }
}

