package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.port.ContentMaterialPort;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.port.PublicationWorkflowPort;
import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.values.CaseSource;
import com.leyon.lumin.foundation.values.ReviewDecision;
import com.leyon.lumin.foundation.values.ReviewCaseEvent;
import com.leyon.lumin.foundation.values.ReviewCaseState;
import com.leyon.lumin.foundation.values.RiskLevel;
import com.leyon.lumin.moderation.domain.MachineReport;
import com.leyon.lumin.moderation.domain.ReviewRoutingPolicy;
import com.leyon.lumin.moderation.domain.ReviewRoutingPolicy.RoutingDecision;
import com.leyon.lumin.moderation.domain.ReviewRoutingPolicy.RoutingOutcome;
import com.leyon.lumin.moderation.domain.ReviewCaseStateMachine;
import com.leyon.lumin.moderation.repository.MachineReportMapper;
import com.leyon.lumin.moderation.repository.MachineReportRow;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import com.leyon.lumin.moderation.repository.ReviewCaseRow;
import com.leyon.lumin.moderation.repository.ReviewDecisionMapper;
import com.leyon.lumin.moderation.repository.ReviewDecisionRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 机审报告接收与 N4 路由（03 §5.1 N3–N5 / 05 §4.3 / T16–T18）：
 * 契约校验 → 绑定核验（case/workflow/version/material/policy 全一致，INV-07）→
 * 报告落库（N3）→ N4 路由（必人审优先；校准无效全人工——K-03 安全默认）→
 * AUTO_* 经抽检闸（5% 命中转人工）→ SYSTEM 裁决记录 + case DECIDED（状态机）
 * → 经 PublicationWorkflowPort 驱动状态机与发布事务（publication 域所有权，
 * Agent/案件域不写公开指针）。
 */
@Service
public class MachineReportService {

    private final ReviewCaseMapper caseMapper;
    private final MachineReportMapper reportMapper;
    private final ReviewDecisionMapper decisionMapper;
    private final ContentMaterialPort contentMaterialPort;
    private final PublicationWorkflowPort publicationWorkflowPort;
    private final AutoSampleGate autoSampleGate;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final boolean calibrationValid;

    public MachineReportService(ReviewCaseMapper caseMapper, MachineReportMapper reportMapper,
                                ReviewDecisionMapper decisionMapper,
                                ContentMaterialPort contentMaterialPort,
                                PublicationWorkflowPort publicationWorkflowPort,
                                AutoSampleGate autoSampleGate, AuditRecorder auditRecorder,
                                ObjectMapper objectMapper,
                                @Value("${lumin.review.calibration-valid:false}")
                                boolean calibrationValid) {
        this.caseMapper = caseMapper;
        this.reportMapper = reportMapper;
        this.decisionMapper = decisionMapper;
        this.contentMaterialPort = contentMaterialPort;
        this.publicationWorkflowPort = publicationWorkflowPort;
        this.autoSampleGate = autoSampleGate;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.calibrationValid = calibrationValid;
    }

    /**
     * 接收结果：errorCode 非 null 时为拒绝（控制器按错误码返回信封，事务内已写的
     * 过期审计随提交留存——03 §4.2「已决后到达记为过期」不被异常回滚吞掉）。
     */
    public record ReceiveResult(String reportId, String workflowState, ErrorCode error) {
    }

    /** 接收机审报告（T18：未知/越界/错摘要/错绑定全拒绝，无 APPROVED、无指针）。 */
    @Transactional
    public ReceiveResult receive(MachineReport report) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (report == null || report.validate() != ErrorCode.OK) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        ReviewCaseRow reviewCase = caseMapper.findById(report.caseId().toString());
        if (reviewCase == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        // 05 §4.3：服务核验 versionId/workflowId/caseId、policyVersion、materialSha256 全部一致
        if (reviewCase.versionId() == null
                || !reviewCase.versionId().equals(report.versionId().toString())
                || !reviewCase.workflowId().equals(report.workflowId().toString())
                || reviewCase.policyVersion() != report.policyVersion()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        ContentMaterialPort.MaterialRef version =
                contentMaterialPort.findVersionMaterial(report.versionId().toString());
        if (version == null || !version.materialSha256().equals(report.materialSha256())) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        // 晚到报告：案件已终态 → 409/B2001 + 过期审计随事务提交留存（03 §4.2）
        if ("DECIDED".equals(reviewCase.state())) {
            auditRecorder.record(new AuditRecorder.AuditEntry("system", "SYSTEM",
                    "machine_report.stale", "review_case", reviewCase.id(),
                    "已决案件收到迟到机审报告", traceId(), now.toInstant(ZoneOffset.UTC)));
            return new ReceiveResult(null, null, ErrorCode.B2001);
        }

        // N3：报告落库（不可变证据）
        MachineReportRow row = toRow(report, reviewCase.id(), now);
        reportMapper.insert(row);

        // N4 路由：authorAgeBand 取自权威源数据链（版本→作品→作者档案；不由报告携带）
        RoutingDecision routing = ReviewRoutingPolicy.route(new ReviewRoutingPolicy.RoutingInput(
                true, calibrationValid,
                AgeBand.valueOf(version.authorAgeBand()), CaseSource.SUBMISSION,
                report.risk(), report.riskFlags(), report.confidence(),
                report.suggestedDecision()));

        String workflowState;
        if (routing.outcome() == RoutingOutcome.AUTO_ACCEPT
                || routing.outcome() == RoutingOutcome.AUTO_REVISE) {
            // 抽检闸：命中 5% 转人工（03 §5.3），未中则系统自动执行
            if (autoSampleGate.sampled(reviewCase.id())) {
                workflowState = routeToHuman(reviewCase, now);
            } else {
                ReviewDecision decision = routing.outcome() == RoutingOutcome.AUTO_ACCEPT
                        ? ReviewDecision.PASS : ReviewDecision.REVISE;
                // 先裁决记录 + 状态迁移（乐观锁用快照 lock），再更新风险/SLA（迁移后 lock+1）
                recordSystemDecision(reviewCase, report, decision, now);
                decideCase(reviewCase, now);
                workflowState = publicationWorkflowPort.executeAutoDecision(
                        reviewCase.workflowId(), report.versionId().toString(),
                        decision.name(),
                        decision.requiresGrade() && report.grade() != null
                                ? report.grade().name() : null,
                        report.policyVersion());
            }
        } else {
            // HUMAN / HOLD：报告已存，案件保持 QUEUED 等待人审（HOLD 挂起原因枚举待裁决）
            workflowState = routeToHuman(reviewCase, now);
        }
        // 案件风险/优先级按报告更新（SLA：HIGH→P0 / MEDIUM→P1 / LOW→P2；
        // 在状态迁移后执行——本更新递增 lock_version，先做会使裁决迁移乐观锁失效）
        caseMapper.updateRiskAndPriority(reviewCase.id(), report.risk().name(),
                priorityOf(report.risk()), "system", now);
        return new ReceiveResult(row.id(), workflowState, null);
    }

    private String routeToHuman(ReviewCaseRow reviewCase, LocalDateTime now) {
        if (reviewCase.workflowId() != null) {
            publicationWorkflowPort.routeToHumanReview(reviewCase.workflowId(),
                    reviewCase.policyVersion());
        }
        auditRecorder.record(new AuditRecorder.AuditEntry("system", "SYSTEM",
                "review_case.route_human", "review_case", reviewCase.id(),
                "机审路由人工队列", traceId(), now.toInstant(ZoneOffset.UTC)));
        return "HUMAN_REVIEW";
    }

    /** N5：SYSTEM 裁决记录（append-only；QUEUED→DECIDED 经案件状态机裁决）。 */
    private void recordSystemDecision(ReviewCaseRow reviewCase, MachineReport report,
                                      ReviewDecision decision, LocalDateTime now) {
        decisionMapper.insert(new ReviewDecisionRow(UUID.randomUUID().toString(),
                reviewCase.id(), "system", "SYSTEM", decision.name(),
                decision.requiresGrade() && report.grade() != null
                        ? report.grade().name() : null,
                "AUTO_ROUTE", "机审低风险自动决定（校准有效，未抽中复核）",
                toJsonList(report.evidenceRefs()), reviewCase.reviewStage(),
                report.policyVersion(), now, now, "system", "system", 1L));
    }

    private void decideCase(ReviewCaseRow reviewCase, LocalDateTime now) {
        var transition = ReviewCaseStateMachine.transit(
                ReviewCaseState.valueOf(reviewCase.state()), ReviewCaseEvent.AUTO_DECIDE,
                reviewCase.reviewStage());
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        int rows = caseMapper.transitionState(reviewCase.id(), transition.targetState().name(),
                reviewCase.reviewStage(), reviewCase.state(), "system", now,
                reviewCase.lockVersion());
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
    }

    /** 风险 → SLA 优先级映射（03 §2.4 / 08 §5 骨架口径）。 */
    private static String priorityOf(RiskLevel risk) {
        return switch (risk) {
            case HIGH -> "P0";
            case MEDIUM -> "P1";
            case LOW -> "P2";
        };
    }

    private MachineReportRow toRow(MachineReport report, String caseId, LocalDateTime now) {
        return new MachineReportRow(UUID.randomUUID().toString(), caseId, "skeleton-agent",
                report.modelVersion(), report.materialSha256(),
                report.grade() == null ? null : report.grade().name(), report.policyVersion(),
                report.risk().name(), toJsonList(report.riskFlags().stream()
                        .map(Enum::name).toList()),
                report.suggestedDecision().name(), report.confidence(),
                toJsonList(report.evidenceRefs()), (int) report.inputTokens(),
                (int) report.outputTokens(), (int) report.durationMs(), now, now, now,
                "system", "system", 1L);
    }

    private String toJsonList(java.util.List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? java.util.List.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 列序列化失败", e);
        }
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.web.ApiEnvelope.currentTraceId();
    }
}
