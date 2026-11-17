package com.leyon.lumin.publication.event;

import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.event.DomainEventSubscriber;
import com.leyon.lumin.foundation.event.InboxRecorder;
import com.leyon.lumin.foundation.web.ApiEnvelope;
import com.leyon.lumin.foundation.event.InboxReceiptRow;
import com.leyon.lumin.publication.repository.PublicationWorkflowMapper;
import com.leyon.lumin.publication.service.PublicationWorkflowPortAdapter;
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
 * ReviewResolved.v1 消费者（N5 执行节点，03 §5.1 / 02 §5.2）：
 * moderation 终局裁决（机审自动 / 人审终局）经事件驱动核心域状态机——
 * PASS/LABEL/LIMIT → 发布事务（APPROVED + 指针 + ContentPublished.v1）；
 * REVISE → REJECTED。消费者事务内写收据（03 §4.3）；
 * 过期/错绑定事件不执行并告警（03 §4.2：迟到事件记为过期）。
 */
@Component
public class ReviewResolvedConsumer implements DomainEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(ReviewResolvedConsumer.class);
    private static final String CONSUMER_NAME = "publication-review-resolved";

    private final PublicationWorkflowPortAdapter workflowPort;
    private final PublicationWorkflowMapper workflowMapper;
    private final InboxRecorder inboxRecorder;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    public ReviewResolvedConsumer(PublicationWorkflowPortAdapter workflowPort,
                                  PublicationWorkflowMapper workflowMapper,
                                  InboxRecorder inboxRecorder, AuditRecorder auditRecorder,
                                  ObjectMapper objectMapper) {
        this.workflowPort = workflowPort;
        this.workflowMapper = workflowMapper;
        this.inboxRecorder = inboxRecorder;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public Set<String> supportedEventTypes() {
        return Set.of("REVIEW_RESOLVED_V1");
    }

    @Override
    @Transactional
    public void handle(UUID eventId, String payloadJson) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            inboxRecorder.record(CONSUMER_NAME, eventId.toString(), now);
        } catch (DuplicateKeyException e) {
            return;
        }
        JsonNode payload = objectMapper.readTree(payloadJson);
        String workflowId = payload.get("workflowId").asString();
        String versionId = payload.get("versionId").asString();
        String decision = payload.get("decision").asString();
        // NullNode.asString() 会返回 "null" 字符串——显式判空（grade=null 时转 null）
        JsonNode gradeNode = payload.get("grade");
        String grade = gradeNode == null || gradeNode.isNull()
                ? null : gradeNode.asString();
        long policyVersion = payload.get("policyVersion").asLong();

        var workflow = workflowMapper.findById(workflowId);
        if (workflow == null) {
            log.error("ReviewResolved 核验失败（流程不存在）workflowId={}", workflowId);
            auditRecorder.record(new AuditRecorder.AuditEntry("system", "SYSTEM",
                    "review_resolved.verify_failed", "publication_workflow", workflowId,
                    "事件与权威记录不一致", traceId(), now.toInstant(ZoneOffset.UTC)));
            return;
        }
        try {
            workflowPort.executeReviewResolved(workflowId, versionId, decision, grade,
                    policyVersion);
        } catch (com.leyon.lumin.foundation.LuminBusinessException e) {
            log.error("ReviewResolved 执行被拒 workflowId={} decision={} code={}",
                    workflowId, decision, e.code().name());
            auditRecorder.record(new AuditRecorder.AuditEntry("system", "SYSTEM",
                    "review_resolved.rejected", "publication_workflow", workflowId,
                    "裁决执行被拒：" + e.code().name(), traceId(), now.toInstant(ZoneOffset.UTC)));
        }
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.web.ApiEnvelope.currentTraceId();
    }
}
