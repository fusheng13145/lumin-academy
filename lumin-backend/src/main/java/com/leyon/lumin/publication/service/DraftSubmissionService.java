package com.leyon.lumin.publication.service;

import com.leyon.lumin.authoring.domain.SubmissionGate;
import com.leyon.lumin.authoring.service.DraftLifecycleService;
import com.leyon.lumin.catalog.domain.TargetRef;
import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;

import com.leyon.lumin.foundation.event.OutboxAppender;
import com.leyon.lumin.foundation.event.OutboxEventRow;
import com.leyon.lumin.foundation.storage.ContentObjectStore;
import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.values.CopyrightStatus;
import com.leyon.lumin.foundation.values.DraftState;
import com.leyon.lumin.foundation.values.TargetType;
import com.leyon.lumin.authoring.api.dto.SubmissionReceipt;
import com.leyon.lumin.identity.domain.AgeAssessment;
import com.leyon.lumin.identity.service.ProfileService;
import com.leyon.lumin.publication.repository.PublicationWorkflowMapper;
import com.leyon.lumin.publication.repository.PublicationWorkflowRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * 草稿提交编排（03 §4.1 第 2–7 条 / R04；编排权归 publication 域——02 §3.1 允许边
 * publication→authoring/catalog/identity/foundation）：
 * 归属/年龄/版权/同目标单流程闸门（authoring.domain.SubmissionGate）→ 正文对象写入
 * （事务外，失败留孤儿对象 24h 清理）→ **同事务**：authoring 不可变版本 + 本域发布流程 +
 * Outbox(ContentSubmitted.v1) + 审计 + draft=SUBMITTED（INV-06）→ 202 受理语义。
 *
 * <p>**骨架登记**：policyVersion 固定 1（configuration 激活链路落地后由其供给）；
 * 机审案件由 Outbox 消费者创建（批次后续），流程停留 SUBMITTED；年龄评估为档案
 * 骨架口径（ProfileService），AgePolicy.assess 证据链接入后替换。
 */
@Service
public class DraftSubmissionService {

    /** 骨架期策略版本（configuration ACTIVE 基线落地后替换为真实 revision）。 */
    private static final long SKELETON_POLICY_VERSION = 1L;

    private final DraftLifecycleService draftLifecycleService;
    private final WorkService workService;
    private final ProfileService profileService;
    private final PublicationWorkflowMapper workflowMapper;
    private final OutboxAppender outboxAppender;
    private final AuditRecorder auditRecorder;
    private final ContentObjectStore objectStore;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public DraftSubmissionService(DraftLifecycleService draftLifecycleService,
                                  WorkService workService, ProfileService profileService,
                                  PublicationWorkflowMapper workflowMapper,
                                  OutboxAppender outboxAppender, AuditRecorder auditRecorder,
                                  ContentObjectStore objectStore,
                                  tools.jackson.databind.ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.draftLifecycleService = draftLifecycleService;
        this.workService = workService;
        this.profileService = profileService;
        this.workflowMapper = workflowMapper;
        this.outboxAppender = outboxAppender;
        this.auditRecorder = auditRecorder;
        this.objectStore = objectStore;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public SubmissionReceipt submit(String subject, String draftId, String ifMatch) {
        long expectedLock = com.leyon.lumin.foundation.web.IfMatch.requireValid(ifMatch);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        DraftLifecycleService.DraftSnapshot draft = draftLifecycleService.loadDraft(draftId);
        WorkService.WorkSummary work = workService.requireOwnedWork(draft.workId(), subject);
        TargetRef target = new TargetRef(TargetType.valueOf(draft.targetType()),
                UUID.fromString(draft.workId()),
                draft.chapterId() == null ? null : UUID.fromString(draft.chapterId()));

        // 提交闸门（03 §2.2 / §4.1 第 2 条）：年龄评估为档案骨架口径
        ProfileService.ProfileSummary profile = profileService.requireProfile(subject);
        AgeAssessment assessment = assess(profile);
        boolean hasOpenWorkflow = workflowMapper.countOpenByTarget(
                target.targetType().name(), target.targetId().toString()) > 0;
        ErrorCode gate = SubmissionGate.check(target, DraftState.valueOf(draft.state()),
                CopyrightStatus.valueOf(work.copyrightStatus()), assessment, hasOpenWorkflow);
        if (gate != ErrorCode.OK) {
            throw new LuminBusinessException(gate);
        }

        // 正文对象（仅 CHAPTER）：content/{versionId}/{sha256}.txt，事务外写入（03 §4.1 第 5 条）
        String versionId = UUID.randomUUID().toString();
        String objectKey = null;
        String bodySha256 = null;
        Integer byteLength = null;
        if (target.targetType() == TargetType.CHAPTER) {
            byte[] body = draft.bodyText().getBytes(StandardCharsets.UTF_8);
            bodySha256 = sha256Hex(body);
            byteLength = body.length;
            objectKey = "content/" + versionId + "/" + bodySha256 + ".txt";
            objectStore.putImmutable(objectKey, body);
        }

        // INV-06：同事务——authoring 版本 + 本域流程 + Outbox + 审计 + draft 态
        final String fObjectKey = objectKey;
        final String fBodySha256 = bodySha256;
        final Integer fByteLength = byteLength;
        return transactionTemplate.execute(status -> {
            DraftLifecycleService.VersionPersisted persisted = draftLifecycleService
                    .persistSubmission(draft, versionId, fObjectKey, fBodySha256, fByteLength,
                            SKELETON_POLICY_VERSION, expectedLock, subject, now);
            String workflowId = UUID.randomUUID().toString();
            workflowMapper.insert(new PublicationWorkflowRow(workflowId, versionId,
                    target.targetType().name(), target.targetId().toString(), "SUBMITTED",
                    SKELETON_POLICY_VERSION, null, null, now, now, subject, subject, 1L));
            outboxAppender.append(new OutboxEventRow(UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), "content_version", versionId, 1L,
                    "CONTENT_SUBMITTED_V1", 1, contentSubmittedPayload(versionId, target,
                    workflowId, profile.effectiveAgeBand(), persisted.materialSha256()),
                    now, null, 0, null, now, now, subject, subject, 1L));
            auditRecorder.record(new AuditRecorder.AuditEntry(subject, "AUTHOR",
                    "draft.submitted", "draft", draft.id(),
                    "提交草稿创建不可变版本与发布流程",
                    com.leyon.lumin.foundation.web.ApiEnvelope.currentTraceId(),
                    now.toInstant(ZoneOffset.UTC)));
            return new SubmissionReceipt(workflowId, persisted.versionId(), "SUBMITTED");
        });
    }

    /** 02 §5.2 ContentSubmitted.v1：Agent 读取最少必要内容；年龄不由 LLM 猜测覆盖。 */
    private String contentSubmittedPayload(String versionId, TargetRef target,
                                           String workflowId, String authorAgeBand,
                                           String materialSha256) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("versionId", versionId);
        payload.put("targetType", target.targetType().name());
        payload.put("targetId", target.targetId().toString());
        payload.put("workflowId", workflowId);
        payload.put("policyVersion", SKELETON_POLICY_VERSION);
        payload.put("authorAgeBand", authorAgeBand);
        payload.put("materialSha256", materialSha256);
        return objectMapper.writeValueAsString(payload);
    }

    /** 骨架年龄评估：ACTIVE 且非 C4 可发布；非 C3 必人审（与路由必人审条件对齐）。 */
    private AgeAssessment assess(ProfileService.ProfileSummary profile) {
        AgeBand band = AgeBand.valueOf(profile.effectiveAgeBand());
        boolean canPublish = "ACTIVE".equals(profile.accountStatus()) && band != AgeBand.C4;
        boolean guardian = band == AgeBand.C0 || band == AgeBand.C1 || band == AgeBand.C2;
        return new AgeAssessment(band, canPublish, guardian, band != AgeBand.C3, false, false,
                canPublish ? null : ErrorCode.A2002);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }
}
