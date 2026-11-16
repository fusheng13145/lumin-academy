package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.port.PublicationWorkflowPort;
import com.leyon.lumin.foundation.values.ReviewDecision;
import com.leyon.lumin.foundation.values.ReviewCaseEvent;
import com.leyon.lumin.foundation.values.ReviewCaseState;
import com.leyon.lumin.moderation.api.dto.CaseView;
import com.leyon.lumin.moderation.api.dto.DecisionRequest;
import com.leyon.lumin.moderation.domain.ReviewCaseStateMachine;
import com.leyon.lumin.moderation.domain.ReviewCaseStateMachine.TransitionResult;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import com.leyon.lumin.moderation.repository.ReviewCaseRow;
import com.leyon.lumin.moderation.repository.ReviewDecisionMapper;
import com.leyon.lumin.moderation.repository.ReviewDecisionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 人审应用服务（03 §2.4 / 05 §3.3；R07）：领取（租约 15 分钟、阶段/回避校验）、
 * 裁决（grade 配对、HIGH 双审、ESCALATE 阶段 3）、队列列表。
 * 状态迁移全部经 {@link ReviewCaseStateMachine} 裁决后持久化；发布/驳回经
 * PublicationWorkflowPort（publication 域所有权，N5）。
 *
 * <p>**骨架登记**：角色校验以替身身份的角色名为准（DemoAuthenticationFilter 有界
 * 替身集合），RolePolicy.authorize 完整矩阵随角色授予链路（role_grant，V2）接线；
 * 回避集合 = 原案件全部 decision 主体（03 §6：复核人不得出现在原案件任一裁决记录）。
 */
@Service
public class ReviewCaseService {

    /** 领取租约（03 §2.4：15 分钟）。 */
    private static final long LEASE_MINUTES = 15;

    private final ReviewCaseMapper caseMapper;
    private final ReviewDecisionMapper decisionMapper;
    private final PublicationWorkflowPort publicationWorkflowPort;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    public ReviewCaseService(ReviewCaseMapper caseMapper,
                             ReviewDecisionMapper decisionMapper,
                             PublicationWorkflowPort publicationWorkflowPort,
                             AuditRecorder auditRecorder, ObjectMapper objectMapper) {
        this.caseMapper = caseMapper;
        this.decisionMapper = decisionMapper;
        this.publicationWorkflowPort = publicationWorkflowPort;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    /** 队列列表（不含正文；priority/completion_due_at/id 排序）。 */
    @Transactional(readOnly = true)
    public List<CaseView> listQueued() {
        return caseMapper.listQueued(100).stream().map(this::toView).toList();
    }

    /** 领取（T20 状态面）：QUEUED/WAITING_SECOND → CLAIMED，租约 15 分钟；
     *  阶段 3 仲裁需 REVIEWER_L2A 替身且未参与原裁决（03 §2.4）。 */
    @Transactional
    public CaseView claim(String subject, String stubRole, String caseId, String ifMatch) {
        long expectedLock = com.leyon.lumin.foundation.web.IfMatch.requireValid(ifMatch);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ReviewCaseRow row = caseMapper.findByIdForUpdate(caseId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        // 角色校验（骨架：替身角色；完整 RolePolicy.authorize 随授予链路接线）
        if (row.reviewStage() == 3) {
            // 03 §2.4：阶段 3 仅未参与原裁决的新 L2 领取（原审全体回避，03 §6）
            if (!"REVIEWER_L2".equals(stubRole)) {
                throw new LuminBusinessException(ErrorCode.A2001);
            }
            boolean participant = decisionMapper.findByCase(caseId).stream()
                    .anyMatch(d -> subject.equals(d.actorId()));
            if (participant) {
                throw new LuminBusinessException(ErrorCode.A2001);
            }
        } else if (!"REVIEWER_L1".equals(stubRole) && !"REVIEWER_L2".equals(stubRole)) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
        TransitionResult transition = ReviewCaseStateMachine.transit(
                ReviewCaseState.valueOf(row.state()), ReviewCaseEvent.CLAIM,
                row.reviewStage());
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        int rows = caseMapper.claim(caseId, transition.targetState().name(), subject,
                now.plusMinutes(LEASE_MINUTES), subject, now, expectedLock);
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                "review_case.claimed", "review_case", caseId,
                "领取案件（租约 15 分钟）", traceId(), now.toInstant(ZoneOffset.UTC)));
        return toView(caseMapper.findById(caseId));
    }

    /** 裁决（T21 状态面）：HIGH 首审 → WAITING_SECOND；ESCALATE → 阶段 3；
     *  最终裁决 DECIDED → 经端口驱动发布/驳回（HIDE/REMOVE/BAN 为治理语义，登记）。 */
    @Transactional
    public CaseView decide(String subject, String stubRole, String caseId, String ifMatch,
                           DecisionRequest request) {
        long expectedLock = com.leyon.lumin.foundation.web.IfMatch.requireValid(ifMatch);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ReviewDecision decision = parseDecision(request);
        ReviewCaseRow row = caseMapper.findByIdForUpdate(caseId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        // 仅当前领取人可裁决；租约过期须重新领取（03 §2.4）
        if (!subject.equals(row.assigneeId())) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (row.leaseExpiresAt() != null && now.isAfter(row.leaseExpiresAt())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        // HIGH 风险双人：首审完成不终局（FIRST_DECISION → WAITING_SECOND）
        boolean highFirstReview = "HIGH".equals(row.risk()) && row.reviewStage() == 1
                && decision != ReviewDecision.ESCALATE;
        ReviewCaseEvent event = highFirstReview ? ReviewCaseEvent.FIRST_DECISION
                : decision == ReviewDecision.ESCALATE ? ReviewCaseEvent.ESCALATE
                : ReviewCaseEvent.DECIDE;
        TransitionResult transition = ReviewCaseStateMachine.transit(
                ReviewCaseState.valueOf(row.state()), event, row.reviewStage());
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        int stage = highFirstReview ? 2
                : transition.targetState() == ReviewCaseState.WAITING_SECOND
                && event == ReviewCaseEvent.ESCALATE ? 3 : row.reviewStage();
        decisionMapper.insert(new ReviewDecisionRow(UUID.randomUUID().toString(),
                row.id(), subject, "HUMAN", decision.name(),
                decision.requiresGrade() ? request.grade() : null,
                request.reasonCode(), request.reason(), toJsonList(request.evidenceRefs()),
                row.reviewStage(), row.policyVersion(), now, now, subject, subject, 1L));
        int migratedStage = highFirstReview ? 2
                : transition.targetState() == ReviewCaseState.WAITING_SECOND
                && event == ReviewCaseEvent.ESCALATE ? 3 : row.reviewStage();
        int rows = caseMapper.transitionState(caseId, transition.targetState().name(),
                migratedStage, row.state(), subject, now, expectedLock);
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        // 终局裁决（DECIDED）驱动发布/驳回；治理语义（HIDE/REMOVE/BAN）随治理批次（登记）
        if (transition.targetState() == ReviewCaseState.DECIDED
                && row.workflowId() != null
                && decision != ReviewDecision.HIDE && decision != ReviewDecision.REMOVE
                && decision != ReviewDecision.BAN) {
            publicationWorkflowPort.executeAutoDecision(row.workflowId(),
                    row.versionId() == null ? null : row.versionId(), decision.name(),
                    request.grade(), row.policyVersion());
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                "review_case.decided", "review_case", caseId,
                "裁决：" + decision.name(), traceId(), now.toInstant(ZoneOffset.UTC)));
        return toView(caseMapper.findById(caseId));
    }

    private ReviewDecision parseDecision(DecisionRequest request) {
        if (request == null || request.decision() == null || request.reasonCode() == null
                || request.reasonCode().isBlank() || request.reason() == null
                || request.reason().isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        ReviewDecision decision;
        try {
            decision = ReviewDecision.valueOf(request.decision());
        } catch (IllegalArgumentException e) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        if (com.leyon.lumin.moderation.domain.ReviewDecisionRule.check(decision,
                parseGrade(request.grade())) != ErrorCode.OK) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return decision;
    }

    private com.leyon.lumin.foundation.values.Grade parseGrade(String grade) {
        if (grade == null) {
            return null;
        }
        try {
            return com.leyon.lumin.foundation.values.Grade.valueOf(grade);
        } catch (IllegalArgumentException e) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
    }

    private CaseView toView(ReviewCaseRow row) {
        return new CaseView(row.id(), row.workId(), row.chapterId(), row.state(),
                row.risk(), row.priority(), row.reviewStage(), row.assigneeId(),
                row.responseDueAt(), row.completionDueAt(), row.lockVersion());
    }

    private String toJsonList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 列序列化失败", e);
        }
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.web.ApiEnvelope.currentTraceId();
    }
}
