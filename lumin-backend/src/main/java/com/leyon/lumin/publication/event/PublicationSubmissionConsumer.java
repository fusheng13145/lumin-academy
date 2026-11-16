package com.leyon.lumin.publication.event;

import com.leyon.lumin.authoring.service.DraftLifecycleService;
import com.leyon.lumin.authoring.service.DraftLifecycleService.MaterialRef;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.event.DomainEventSubscriber;
import com.leyon.lumin.foundation.port.ReviewCasePort;
import com.leyon.lumin.foundation.repository.InboxReceiptMapper;
import com.leyon.lumin.foundation.repository.InboxReceiptRow;
import com.leyon.lumin.foundation.values.PublicationEvent;
import com.leyon.lumin.foundation.values.TargetType;
import com.leyon.lumin.foundation.values.WorkflowState;
import com.leyon.lumin.publication.domain.PublicationStateMachine;
import com.leyon.lumin.publication.domain.TransitionContext;
import com.leyon.lumin.publication.domain.TransitionResult;
import com.leyon.lumin.publication.repository.PublicationWorkflowMapper;
import com.leyon.lumin.publication.repository.PublicationWorkflowRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

/**
 * ContentSubmitted.v1 消费者（N1 加载节点，03 §5.1 / 02 §5.2）：
 * 消费者事务内写收据与领域更新（03 §4.3）。核验 versionId/workflowId/
 * materialSha256/policyVersion 与权威记录一致（05 §4.3）——全部一致则
 * 经 ReviewCasePort 建案并执行状态机 SUBMITTED→AUTO_REVIEW（AUTO_TASK_CLAIMED，
 * 骨架口径：建案即机审任务派发）；核验失败则保持 SUBMITTED + 告警（案件不建，
 * **登记**：N1 失败停流 SUSPENDED 需状态机扩展行，延后 Owner 裁决）。
 */
@Component
public class PublicationSubmissionConsumer implements DomainEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(PublicationSubmissionConsumer.class);
    private static final String CONSUMER_NAME = "publication-workflow-init";

    private final PublicationWorkflowMapper workflowMapper;
    private final DraftLifecycleService draftLifecycleService;
    private final ReviewCasePort reviewCasePort;
    private final InboxReceiptMapper inboxReceiptMapper;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    public PublicationSubmissionConsumer(PublicationWorkflowMapper workflowMapper,
                                         DraftLifecycleService draftLifecycleService,
                                         ReviewCasePort reviewCasePort,
                                         InboxReceiptMapper inboxReceiptMapper,
                                         AuditRecorder auditRecorder,
                                         ObjectMapper objectMapper) {
        this.workflowMapper = workflowMapper;
        this.draftLifecycleService = draftLifecycleService;
        this.reviewCasePort = reviewCasePort;
        this.inboxReceiptMapper = inboxReceiptMapper;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public Set<String> supportedEventTypes() {
        return Set.of("CONTENT_SUBMITTED_V1");
    }

    @Override
    @Transactional
    public void handle(UUID eventId, String payloadJson) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 03 §4.3：消费者事务内写收据；重复投递经唯一约束去重，不重复执行领域动作
        try {
            inboxReceiptMapper.insert(new InboxReceiptRow(CONSUMER_NAME,
                    eventId.toString(), now));
        } catch (DuplicateKeyException e) {
            return;
        }
        JsonNode payload = objectMapper.readTree(payloadJson);
        String versionId = payload.get("versionId").asString();
        String workflowId = payload.get("workflowId").asString();
        String materialSha256 = payload.get("materialSha256").asString();
        long policyVersion = payload.get("policyVersion").asLong();

        // N1 核验（05 §4.3：服务核验 versionId/workflowId/policyVersion/materialSha256 全部一致）
        PublicationWorkflowRow workflow = workflowMapper.findById(workflowId);
        MaterialRef version = draftLifecycleService.findVersionMaterial(versionId);
        boolean consistent = workflow != null
                && WorkflowState.SUBMITTED.name().equals(workflow.state())
                && version != null
                && workflow.versionId().equals(version.versionId())
                && version.materialSha256().equals(materialSha256)
                && version.policyVersion() == policyVersion;
        if (!consistent) {
            // N1 失败路径：案件不建；骨架保持 SUBMITTED + 告警（登记见类注释）
            log.error("ContentSubmitted 核验失败，案件不创建 workflowId={} versionId={}",
                    workflowId, versionId);
            auditRecorder.record(new AuditRecorder.AuditEntry("system", "SYSTEM",
                    "submission.verify_failed", "publication_workflow", workflowId,
                    "事件与权威记录不一致，案件不创建", traceId(), now.toInstant(ZoneOffset.UTC)));
            return;
        }

        String caseId = reviewCasePort.createSubmissionCase(
                new ReviewCasePort.SubmissionCaseRequest(versionId, workflowId,
                        version.workId(), version.chapterId(), policyVersion));
        // 状态机裁决：SUBMITTED →(AUTO_TASK_CLAIMED)→ AUTO_REVIEW（03 §2.3）
        TransitionResult transition = PublicationStateMachine.workflow(
                WorkflowState.SUBMITTED, PublicationEvent.AUTO_TASK_CLAIMED, context());
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        workflowMapper.updateStateAndCase(workflowId, transition.toState().name(),
                caseId, "system", now);
        auditRecorder.record(new AuditRecorder.AuditEntry("system", "SYSTEM",
                "review_case.queued", "review_case", caseId,
                "提交核验通过，案件进入队列", traceId(), now.toInstant(ZoneOffset.UTC)));
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.web.ApiEnvelope.currentTraceId();
    }

    /** 迁移前提（03 §2.3「操作者与前提」列）：由编排事务内权威数据满足。 */
    private static TransitionContext context() {
        return new TransitionContext(null, true, true, true, null, true, true, true,
                true, true, true, true, true, true, false, true, false);
    }
}
