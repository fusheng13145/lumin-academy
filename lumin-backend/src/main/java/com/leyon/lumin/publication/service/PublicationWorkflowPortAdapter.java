package com.leyon.lumin.publication.service;

import com.leyon.lumin.authoring.service.DraftLifecycleService;
import com.leyon.lumin.foundation.port.ContentMaterialPort;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.port.PublicationWorkflowPort;
import com.leyon.lumin.foundation.event.OutboxAppender;
import com.leyon.lumin.foundation.event.OutboxEventRow;
import com.leyon.lumin.foundation.values.PublicationEvent;
import com.leyon.lumin.foundation.values.TargetType;
import com.leyon.lumin.foundation.values.WorkflowState;
import com.leyon.lumin.publication.domain.PublicationStateMachine;
import com.leyon.lumin.publication.domain.TransitionContext;
import com.leyon.lumin.publication.domain.TransitionResult;
import com.leyon.lumin.publication.repository.PublicationPointerMapper;
import com.leyon.lumin.publication.repository.PublicationPointerRow;
import com.leyon.lumin.publication.repository.PublicationWorkflowMapper;
import com.leyon.lumin.publication.repository.PublicationWorkflowRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * 发布流程端口适配器（publication 域所有权：发布流程、发布指针、三态读者判定）。
 * N5 执行（03 §5.1）：核心域状态机裁决 + 发布事务（03 §4.2）——
 * AUTO_PASS → 版本/流程 APPROVED + 原子交换公开指针 + ContentPublished.v1；
 * 章节父作品无已通过版本 → 停留 HUMAN_REVIEW（不将不满足前提的审批提交成功）；
 * AUTO_REVISE → 版本/流程 REJECTED；ROUTE_HUMAN_REVIEW → 停留人工队列。
 * 「检查 ACTIVE 限制与最新身份策略」待 governance V2 迁移落地后接入（登记）。
 */
@Service
public class PublicationWorkflowPortAdapter implements PublicationWorkflowPort {

    private final PublicationWorkflowMapper workflowMapper;
    private final PublicationPointerMapper pointerMapper;
    private final DraftLifecycleService draftLifecycleService;
    private final ContentMaterialPort contentMaterialPort;
    private final OutboxAppender outboxAppender;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    public PublicationWorkflowPortAdapter(PublicationWorkflowMapper workflowMapper,
                                          PublicationPointerMapper pointerMapper,
                                          DraftLifecycleService draftLifecycleService,
                                  ContentMaterialPort contentMaterialPort,
                                          OutboxAppender outboxAppender,
                                          AuditRecorder auditRecorder,
                                          ObjectMapper objectMapper) {
        this.workflowMapper = workflowMapper;
        this.pointerMapper = pointerMapper;
        this.draftLifecycleService = draftLifecycleService;
        this.contentMaterialPort = contentMaterialPort;
        this.outboxAppender = outboxAppender;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public String executeAutoDecision(String workflowId, String versionId, String decision,
                                      String grade, long policyVersion) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PublicationWorkflowRow workflow = workflowMapper.findByIdForUpdate(workflowId);
        if (workflow == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        ContentMaterialPort.MaterialRef version = contentMaterialPort.findVersionMaterial(versionId);
        if (version == null || !workflow.versionId().equals(version.versionId())) {
            throw new LuminBusinessException(ErrorCode.D2002);
        }

        // 03 §2.3：章节发布须父作品已有可见已通过版本，否则停留 HUMAN_REVIEW
        PublicationEvent event = resolveEvent(workflow, versionId, decision);

        TransitionResult transition = PublicationStateMachine.workflow(
                WorkflowState.valueOf(workflow.state()), event, context(version.targetType()));
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        workflowMapper.updateState(workflowId, transition.toState().name(), null,
                "system", now);
        draftLifecycleService.transitionVersionState(versionId,
                transition.versionState().name(), grade, "system", now);

        if (event == PublicationEvent.AUTO_PASS) {
            publishPointer(workflow, versionId, now);
        }
        auditRecorder.record(new AuditRecorder.AuditEntry("system", "SYSTEM",
                "workflow.auto_decision", "publication_workflow", workflowId,
                "机审自动裁决：" + event.name(), traceId(), now.toInstant(ZoneOffset.UTC)));
        return transition.toState().name();
    }

    @Override
    @Transactional
    public String routeToHumanReview(String workflowId, long policyVersion) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PublicationWorkflowRow workflow = workflowMapper.findByIdForUpdate(workflowId);
        if (workflow == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        TransitionResult transition = PublicationStateMachine.workflow(
                WorkflowState.valueOf(workflow.state()), PublicationEvent.ROUTE_HUMAN_REVIEW,
                context("CHAPTER"));
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        workflowMapper.updateState(workflowId, transition.toState().name(), null,
                "system", now);
        return transition.toState().name();
    }

    /** 事件解析：REVISE → AUTO_REVISE；章节父作品未发布 → ROUTE_HUMAN_REVIEW；否则 AUTO_PASS。 */
    private PublicationEvent resolveEvent(PublicationWorkflowRow workflow, String versionId,
                                          String decision) {
        if ("REVISE".equals(decision)) {
            return PublicationEvent.AUTO_REVISE;
        }
        if ("CHAPTER".equals(workflow.targetType())) {
            ContentMaterialPort.MaterialRef version = contentMaterialPort.findVersionMaterial(versionId);
            PublicationPointerRow workPointer = version == null ? null
                    : pointerMapper.findByTarget("WORK", version.workId());
            if (workPointer == null) {
                return PublicationEvent.ROUTE_HUMAN_REVIEW;
            }
        }
        return PublicationEvent.AUTO_PASS;
    }

    /** 原子交换公开指针（INV-02）：首发插入，修订单调递增交换 + ContentPublished.v1。 */
    private void publishPointer(PublicationWorkflowRow workflow, String versionId,
                                LocalDateTime now) {
        String targetType = workflow.targetType();
        String targetId = workflow.targetId();
        ContentMaterialPort.MaterialRef version = contentMaterialPort.findVersionMaterial(versionId);
        String workId = version == null ? null : version.workId();
        PublicationPointerRow existing =
                pointerMapper.findByTarget(targetType, targetId);
        String previousVersionId = existing == null ? null : existing.versionId();
        boolean isNewChapter = "CHAPTER".equals(targetType) && existing == null;
        if (existing == null) {
            pointerMapper.insert(new PublicationPointerRow(UUID.randomUUID().toString(),
                    targetType, targetId, versionId, now, 1L, now, now,
                    "system", "system", 1L));
        } else {
            int rows = pointerMapper.swapVersion(existing.id(), versionId, now,
                    existing.publicationRevision() + 1, "system", now,
                    existing.lockVersion());
            if (rows == 0) {
                throw new LuminBusinessException(ErrorCode.B2002);
            }
        }
        outboxAppender.append(new OutboxEventRow(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "content_version", versionId, 2L,
                "CONTENT_PUBLISHED_V1", 1, contentPublishedPayload(targetType, targetId,
                workId, versionId, previousVersionId, isNewChapter, now),
                now, null, 0, null, now, now, "system", "system", 1L));
    }

    private String contentPublishedPayload(String targetType, String targetId, String workId,
                                           String versionId, String previousVersionId,
                                           boolean isNewChapter, LocalDateTime now) {
        // 02 §5.2 ContentPublished.v1：P1 追更只消费 isNewChapter=true；通知不携带正文
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetType", targetType);
        payload.put("targetId", targetId);
        payload.put("workId", workId);
        payload.put("versionId", versionId);
        payload.put("previousVersionId", previousVersionId);
        payload.put("isNewChapter", isNewChapter);
        payload.put("publishedAt", now.toString());
        return objectMapper.writeValueAsString(payload);
    }

    private static TransitionContext context(String targetType) {
        return new TransitionContext(
                targetType == null ? null : TargetType.valueOf(targetType),
                true, true, true, null, true, true, true, true, true, true,
                true, true, true, false, true, false);
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.web.ApiEnvelope.currentTraceId();
    }
}
