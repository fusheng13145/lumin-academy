package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import com.leyon.lumin.foundation.event.OutboxAppender;
import com.leyon.lumin.foundation.event.OutboxEventRow;
import com.leyon.lumin.foundation.port.ContentMaterialPort;
import com.leyon.lumin.foundation.port.ReviewerQueueScopePort;
import com.leyon.lumin.foundation.values.ReviewDecision;
import com.leyon.lumin.foundation.values.ReviewCaseEvent;
import com.leyon.lumin.foundation.values.ReviewCaseState;
import com.leyon.lumin.foundation.web.ReceiptView;
import com.leyon.lumin.moderation.api.dto.CaseDetailView;
import com.leyon.lumin.moderation.api.dto.DecisionItemView;
import com.leyon.lumin.moderation.api.dto.DecisionRequest;
import com.leyon.lumin.moderation.domain.ReviewCaseStateMachine;
import com.leyon.lumin.moderation.domain.ReviewCaseStateMachine.TransitionResult;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import com.leyon.lumin.moderation.repository.ReviewCaseRow;
import com.leyon.lumin.moderation.repository.ReviewDecisionMapper;
import com.leyon.lumin.moderation.repository.ReviewDecisionRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
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

    /** 领取租约（03 §2.4：15 分钟）；队列读取角色。 */
    private static final long LEASE_MINUTES = 15;
    private static final Set<String> QUEUE_READER_ROLES =
            Set.of("REVIEWER_L1", "REVIEWER_L2", "LEGAL");

    private final ReviewCaseMapper caseMapper;
    private final ReviewDecisionMapper decisionMapper;
    private final OutboxAppender outboxAppender;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final ContentMaterialPort contentMaterialPort;
    private final ReviewerQueueScopePort reviewerQueueScopePort;
    private final String cursorKey;

    public ReviewCaseService(ReviewCaseMapper caseMapper,
                             ReviewDecisionMapper decisionMapper,
                             OutboxAppender outboxAppender,
                             AuditRecorder auditRecorder, ObjectMapper objectMapper,
                             ContentMaterialPort contentMaterialPort,
                             ReviewerQueueScopePort reviewerQueueScopePort,
                             @Value("${lumin.cursor.key}") String cursorKey) {
        this.caseMapper = caseMapper;
        this.decisionMapper = decisionMapper;
        this.outboxAppender = outboxAppender;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.contentMaterialPort = contentMaterialPort;
        this.reviewerQueueScopePort = reviewerQueueScopePort;
        this.cursorKey = cursorKey;
    }

    public record Page(List<CaseDetailView> items, String nextCursor) {
    }

    /** 队列列表（契约 CasePage：priority/completion_due_at/id 签名游标）。 */
    @Transactional(readOnly = true)
    public Page listCases(String subject, String role, String cursorToken, Integer limitParam) {
        if (!QUEUE_READER_ROLES.contains(role)) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
        requireLimit(limitParam);
        int limit = CursorCodec.resolveLimit(limitParam);
        ReviewerQueueScopePort.QueueScope queueScope =
                reviewerQueueScopePort.resolve(subject, role);
        if (!queueScope.global() && queueScope.queueIds().isEmpty()) {
            // session 模式无有效 queue scope：空页失败关闭，不泄露其他队列案件
            return new Page(List.of(), null);
        }
        String scopeKey = queueScope.global() ? "ALL"
                : String.join(",", queueScope.queueIds().stream().sorted().toList());
        String filterDigest = CursorCodec.filterDigest("list=review-cases;viewer=" + subject
                + ";role=" + role + ";queueScope=" + scopeKey);
        int cursorRank = 0;
        LocalDateTime cursorDue = LocalDateTime.of(1970, 1, 1, 0, 0);
        UUID cursorId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        if (cursorToken != null && !cursorToken.isBlank()) {
            CursorCodec.DecodedCursor decoded = CursorCodec.decode(cursorToken,
                    cursorKey.getBytes(StandardCharsets.UTF_8), Instant.now(), filterDigest);
            requireCursor(decoded, subject);
            String[] sortKey = decoded.payload().sortKeyValue().split("\\|", 2);
            if (sortKey.length != 2) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
            cursorRank = Integer.parseInt(sortKey[0]);
            cursorDue = LocalDateTime.parse(sortKey[1]);
            cursorId = decoded.payload().lastId();
        }
        List<ReviewCaseRow> rows = caseMapper.listQueued(subject,
                "REVIEWER_L1".equals(role), "LEGAL".equals(role),
                queueScope.global(), queueScope.global() ? null
                        : queueScope.queueIds().stream().sorted().toList(),
                cursorRank, cursorDue, cursorId.toString(), limit + 1);
        boolean hasNext = rows.size() > limit;
        List<ReviewCaseRow> pageRows = hasNext ? rows.subList(0, limit) : rows;
        java.util.Map<String, List<ReviewDecisionRow>> decisionsByCase = pageRows.isEmpty()
                ? java.util.Map.of()
                : decisionMapper.findByCaseIds(pageRows.stream().map(ReviewCaseRow::id).toList())
                        .stream().collect(java.util.stream.Collectors.groupingBy(
                                ReviewDecisionRow::caseId));
        List<String> materialVersionIds = pageRows.stream()
                .map(ReviewCaseRow::versionId).filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<String, String> ageBandByVersion = materialVersionIds.isEmpty()
                ? java.util.Map.of()
                : contentMaterialPort.findVersionMaterials(materialVersionIds).stream()
                        .filter(material -> material.authorAgeBand() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                ContentMaterialPort.MaterialRef::versionId,
                                ContentMaterialPort.MaterialRef::authorAgeBand));
        List<CaseDetailView> items = pageRows.stream()
                .map(row -> toDetail(row, decisionsByCase.getOrDefault(row.id(), List.of()),
                        row.versionId() == null ? "C4"
                                : ageBandByVersion.getOrDefault(row.versionId(), "C4")))
                .toList();
        String nextCursor = null;
        if (hasNext) {
            ReviewCaseRow last = pageRows.get(pageRows.size() - 1);
            nextCursor = CursorCodec.encode(new CursorCodec.Payload(
                    priorityRank(last.priority()) + "|" + last.completionDueAt(),
                    UUID.fromString(last.id()), filterDigest, UUID.fromString(subject),
                    Instant.now()), cursorKey.getBytes(StandardCharsets.UTF_8));
        }
        return new Page(items, nextCursor);
    }

    /** 领取（T20 状态面）：QUEUED/WAITING_SECOND → CLAIMED，租约 15 分钟；
     *  阶段 3 仲裁需 REVIEWER_L2A 替身且未参与原裁决（03 §2.4）。 */
    @Transactional
    public ReceiptView claim(String subject, String stubRole, String caseId, String ifMatch) {
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
        return new ReceiptView(caseId, "CASE", transition.targetState().name(),
                expectedLock + 1);
    }

    /** 裁决（T21 状态面）：HIGH 首审 → WAITING_SECOND；ESCALATE → 阶段 3；
     *  最终裁决 DECIDED → 经端口驱动发布/驳回（HIDE/REMOVE/BAN 为治理语义，登记）。 */
    @Transactional
    public ReceiptView decide(String subject, String stubRole, String caseId, String ifMatch,
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
        // 终局裁决（DECIDED）写 ReviewResolved.v1——publication 消费者执行状态机与发布事务
        // （02 §5.2；HIDE/REMOVE/BAN 为治理语义，随治理批次接线，登记）
        if (transition.targetState() == ReviewCaseState.DECIDED
                && row.workflowId() != null
                && decision != ReviewDecision.HIDE && decision != ReviewDecision.REMOVE
                && decision != ReviewDecision.BAN) {
            java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("caseId", row.id());
            payload.put("workflowId", row.workflowId());
            payload.put("versionId", row.versionId());
            payload.put("decision", decision.name());
            payload.put("grade", decision.requiresGrade() ? request.grade() : null);
            payload.put("policyVersion", row.policyVersion());
            outboxAppender.append(new OutboxEventRow(UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), "review_case", row.id(), 1L,
                    "REVIEW_RESOLVED_V1", 1, objectMapper.writeValueAsString(payload),
                    LocalDateTime.now(ZoneOffset.UTC), null, 0, null, now, now,
                    subject, subject, 1L));
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                "review_case.decided", "review_case", caseId,
                "裁决：" + decision.name(), traceId(), now.toInstant(ZoneOffset.UTC)));
        return new ReceiptView(caseId, "CASE", transition.targetState().name(),
                expectedLock + 1);
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

    private CaseDetailView toDetail(ReviewCaseRow row, List<ReviewDecisionRow> decisionRows,
                                    String authorAgeBand) {
        List<DecisionItemView> decisions = decisionRows.stream()
                .map(this::toDecision)
                .toList();
        return new CaseDetailView(row.id(), row.workId(), row.versionId(), row.state(),
                row.risk(), row.priority(), authorAgeBand, row.completionDueAt(),
                row.leaseExpiresAt(), row.lockVersion(), decisions);
    }

    private DecisionItemView toDecision(ReviewDecisionRow row) {
        return new DecisionItemView(row.id(), row.actorId(), row.decision(), row.grade(),
                row.reasonCode(), row.reason(), parseEvidence(row.evidenceRefs()),
                row.createdAt());
    }

    private List<String> parseEvidence(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private int priorityRank(String priority) {
        return switch (priority) {
            case "P0" -> 1;
            case "P1" -> 2;
            case "P2" -> 3;
            case "P3" -> 4;
            default -> 5;
        };
    }

    private void requireLimit(Integer limitParam) {
        if (CursorCodec.validateLimit(limitParam) != ErrorCode.OK) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
    }

    private void requireCursor(CursorCodec.DecodedCursor decoded, String subject) {
        if (decoded.code() != ErrorCode.OK
                || !decoded.payload().subjectId().toString().equals(subject)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
    }

    private String toJsonList(List<String> values) {
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

