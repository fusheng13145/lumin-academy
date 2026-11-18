package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.port.CaseOwnershipPort;
import com.leyon.lumin.moderation.domain.AppealStateMachine;
import com.leyon.lumin.moderation.repository.AppealMapper;
import com.leyon.lumin.moderation.repository.AppealRow;
import com.leyon.lumin.moderation.repository.ReviewDecisionActorMapper;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 申诉应用服务（03 §2.4 appeal / §6；T24/T25）：
 * 创建（本人案件 + 同案件唯一在途）→ 复核（**原审全体回避**——复核人不得出现在
 * 原案件任一裁决记录；首次有效复核在事务内 OPEN→IN_REVIEW→终态并记录两迁移）
 * → UPHELD 维持 / OVERTURNED 改判。改判不直接把库改 ACTIVE——临时措施解除
 * 经治理域 RestrictionStateMachine（REVOKED），终局/解约措施不可改判恢复传播。
 */
@Service
public class AppealService {

    /** 受理/完成 SLA（08 §5 P2 档骨架口径：24h 受理 / 72h 完成）。 */
    private static final long ACCEPTED_SLA_HOURS = 24;
    private static final long COMPLETION_SLA_HOURS = 72;

    private final AppealMapper appealMapper;
    private final ReviewCaseMapper reviewCaseMapper;
    private final ReviewDecisionActorMapper decisionActorMapper;
    private final CaseOwnershipPort caseOwnershipPort;
    private final AuditRecorder auditRecorder;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public AppealService(AppealMapper appealMapper, ReviewCaseMapper reviewCaseMapper,
                         ReviewDecisionActorMapper decisionActorMapper,
                         CaseOwnershipPort caseOwnershipPort, AuditRecorder auditRecorder,
                         tools.jackson.databind.ObjectMapper objectMapper) {
        this.appealMapper = appealMapper;
        this.reviewCaseMapper = reviewCaseMapper;
        this.decisionActorMapper = decisionActorMapper;
        this.caseOwnershipPort = caseOwnershipPort;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    /** 创建申诉（03 §6：仅针对本人案件；同案件最多一条未终结申诉）。 */
    @Transactional
    public String createAppeal(String subject, String caseId, String reason,
                               List<String> evidenceRefs) {
        if (caseId == null || reason == null || reason.isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        var reviewCase = reviewCaseMapper.findById(caseId);
        if (reviewCase == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        // 本人案件校验（03 §6）：work.authorId 经 foundation 端口取权威记录（INV-07）
        String authorId = caseOwnershipPort.authorIdOfWork(reviewCase.workId());
        if (authorId == null || !authorId.equals(subject)) {
            // 他人案件：统一 404 防枚举（05 §2）
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (appealMapper.findOpenByCaseId(caseId) != null) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AppealRow row = new AppealRow(UUID.randomUUID().toString(), caseId, subject,
                reason, toJson(evidenceRefs), null, "OPEN", null,
                now.plusHours(ACCEPTED_SLA_HOURS), now.plusHours(COMPLETION_SLA_HOURS),
                now, now, subject, subject, 1L);
        appealMapper.insert(row);
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, "AUTHOR",
                "appeal.created", "appeal", row.id(), "申诉创建",
                traceId(), now.toInstant(ZoneOffset.UTC)));
        return row.id();
    }

    /** 申诉详情（仅申请人本人可见——05 §2 统一 404 防枚举）。 */
    @Transactional(readOnly = true)
    public AppealRow getAppeal(String subject, String appealId) {
        AppealRow appeal = appealId == null ? null : appealMapper.findById(appealId);
        if (appeal == null || !appeal.applicantId().equals(subject)) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return appeal;
    }

    /**
     * 复核（03 §6：原审全体回避；首次有效复核在事务内 OPEN→IN_REVIEW→终态）。
     * 返回迁移后的申诉状态（UPHELD / OVERTURNED）。
     */
    @Transactional
    public String reviewAppeal(String reviewer, String appealId, boolean uphold,
                               String decisionReason) {
        if (decisionReason == null || decisionReason.isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AppealRow appeal = appealId == null ? null : appealMapper.findById(appealId);
        if (appeal == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        // 原审回避：复核人不得为原案件任一裁决主体（03 §6 / INV-05）
        List<String> actors = decisionActorMapper.findActorIdsByCaseId(appeal.caseId());
        if (actors.contains(reviewer)) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
        var first = AppealStateMachine.transit(
                com.leyon.lumin.foundation.values.AppealState.valueOf(appeal.state()),
                com.leyon.lumin.foundation.values.AppealEvent.START_REVIEW);
        if (!first.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        var second = AppealStateMachine.transit(first.targetState(),
                uphold ? com.leyon.lumin.foundation.values.AppealEvent.UPHELD
                        : com.leyon.lumin.foundation.values.AppealEvent.OVERTURNED);
        if (!second.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        int rows = appealMapper.transition(appealId, second.targetState().name(), reviewer,
                decisionReason, appeal.state(), reviewer, now, appeal.lockVersion());
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(reviewer, "REVIEWER_L1",
                "appeal.reviewed", "appeal", appealId,
                "申诉复核：" + (uphold ? "UPHELD" : "OVERTURNED")
                        + "（改判不直接改库，解除经治理域）",
                traceId(), now.toInstant(ZoneOffset.UTC)));
        return second.targetState().name();
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 列序列化失败", e);
        }
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.contract.ApiEnvelope.currentTraceId();
    }
}

