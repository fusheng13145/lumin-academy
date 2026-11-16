package com.leyon.lumin.publication.service;

import com.leyon.lumin.authoring.service.DraftLifecycleService;
import com.leyon.lumin.authoring.service.DraftLifecycleService.PublishedVersionRef;
import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.catalog.service.WorkService.WorkSummary;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.storage.ContentObjectStore;
import com.leyon.lumin.foundation.values.AccountStatus;
import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.values.Grade;
import com.leyon.lumin.identity.domain.AgePolicy;
import com.leyon.lumin.foundation.values.TargetType;
import com.leyon.lumin.foundation.values.VersionState;
import com.leyon.lumin.foundation.values.WorkflowState;
import com.leyon.lumin.identity.service.ProfileService;
import com.leyon.lumin.identity.service.ProfileService.ProfileSummary;
import com.leyon.lumin.publication.domain.VisibilityPolicy;
import com.leyon.lumin.publication.domain.VisibilityDecision;
import com.leyon.lumin.publication.domain.VisibilityInput;
import com.leyon.lumin.publication.repository.PublicationPointerMapper;
import com.leyon.lumin.publication.repository.PublicationPointerRow;
import com.leyon.lumin.publication.repository.PublicationWorkflowMapper;
import com.leyon.lumin.publication.repository.PublicationWorkflowRow;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 读者三态裁决服务（03 §3.1 / R05；R09）：详情与正文逐条经
 * {@link VisibilityPolicy} 纯函数裁决；列表为 SQL 等价过滤（登记：列表
 * 不逐条跑纯函数，语义等价由 T27 覆盖）。工作流核验数据经 authoring 端口
 * （publication→authoring 允许边）；「检查 ACTIVE 限制」待 governance V2（空集）。
 */
@Service
public class ReaderService {

    private final PublicationPointerMapper pointerMapper;
    private final PublicationWorkflowMapper workflowMapper;
    private final DraftLifecycleService draftLifecycleService;
    private final WorkService workService;
    private final ProfileService profileService;
    private final com.leyon.lumin.foundation.storage.ContentObjectStore objectStore;

    public ReaderService(PublicationPointerMapper pointerMapper,
                         PublicationWorkflowMapper workflowMapper,
                         DraftLifecycleService draftLifecycleService, WorkService workService,
                         ProfileService profileService,
                         com.leyon.lumin.foundation.storage.ContentObjectStore objectStore) {
        this.pointerMapper = pointerMapper;
        this.workflowMapper = workflowMapper;
        this.draftLifecycleService = draftLifecycleService;
        this.workService = workService;
        this.profileService = profileService;
        this.objectStore = objectStore;
    }

    /** 读者可见的作品详情（05 §3.4 getWork：最新公开元数据，未授权统一 404）。 */
    public Optional<WorkView> getWork(String subject, String workId) {
        VisibilityDecision decision = decide(subject, TargetType.WORK, workId);
        if (!decision.allowed()) {
            return Optional.empty();
        }
        PublishedVersionRef version = draftLifecycleService
                .findPublishedVersion(decision.versionId());
        return Optional.of(new WorkView(version.versionId(), version.workId(),
                version.title(), version.synopsis(), version.categoryId(), version.tags(),
                version.grade()));
    }

    /** 读者可见的章节正文（05 §3.4 readChapter：不接受 versionId 指定历史版本）。 */
    public Optional<ContentView> readChapter(String subject, String chapterId) {
        VisibilityDecision decision = decide(subject, TargetType.CHAPTER, chapterId);
        if (!decision.allowed()) {
            return Optional.empty();
        }
        PublishedVersionRef version = draftLifecycleService
                .findPublishedVersion(decision.versionId());
        if (version.objectKey() == null) {
            return Optional.empty();
        }
        String body = new String(objectStore.read(version.objectKey()),
                java.nio.charset.StandardCharsets.UTF_8);
        return Optional.of(new ContentView(version.chapterId(), version.versionId(),
                version.title(), body, version.grade()));
    }

    /** 作品列表（骨架：SQL 等价过滤 + AgePolicy 逐条年龄过滤；游标分页随 P1 批次）。 */
    public java.util.List<WorkView> listWorks(String subject) {
        ProfileSummary requester = profileService.requireProfile(subject);
        var rows = pointerMapper.listPublishedWorks(100);
        return rows.stream()
                .filter(row -> {
                    PublishedVersionRef version = draftLifecycleService
                            .findPublishedVersion(row.versionId());
                    return version != null && AgePolicy.canReadPublic(
                            AgeBand.valueOf(requester.effectiveAgeBand()),
                            Grade.valueOf(version.grade()));
                })
                .map(row -> draftLifecycleService.findPublishedVersion(row.versionId()))
                .filter(java.util.Objects::nonNull)
                .map(version -> new WorkView(version.versionId(), version.workId(),
                        version.title(), version.synopsis(), version.categoryId(),
                        version.tags(), version.grade()))
                .toList();
    }

    /** 三态裁决组装（03 §3.1 八步输入全部来自权威记录，INV-07）。 */
    private VisibilityDecision decide(String subject, TargetType targetType, String targetId) {
        PublicationPointerRow pointer = pointerMapper.findByTarget(targetType.name(), targetId);
        PublishedVersionRef version = pointer == null ? null
                : draftLifecycleService.findPublishedVersion(pointer.versionId());
        if (pointer == null || version == null) {
            // 无发布指针且无已发布事实：不告警（03 §3.1 步骤 3）
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        PublicationWorkflowRow workflow =
                workflowMapper.findByVersionId(pointer.versionId());
        WorkSummary work = workService.getWorkSummary(version.workId());
        ProfileSummary requester = profileService.requireProfile(subject);
        VisibilityDecision decision = VisibilityPolicy.decide(new VisibilityInput(
                AgeBand.valueOf(requester.effectiveAgeBand()),
                AccountStatus.valueOf(requester.accountStatus()),
                targetType, targetId,
                work == null ? null : com.leyon.lumin.foundation.values.BusinessState
                        .valueOf(work.businessState()),
                work == null ? null : com.leyon.lumin.foundation.values.BusinessState
                        .valueOf(work.businessState()),
                Set.of(), // 生效治理限制：restriction 表随 governance V2 迁移落地
                version.versionId(), VersionState.valueOf(version.state()),
                version.grade() == null ? null : Grade.valueOf(version.grade()),
                workflow == null ? null : workflow.id(),
                workflow == null ? null : WorkflowState.valueOf(workflow.state()),
                workflow == null ? null : workflow.versionId(),
                version.policyVersion(), version.policyVersion(),
                pointer.versionId(), targetId,
                "CHAPTER".equals(targetType) ? version.chapterId() : version.workId(),
                true, true, true,
                false, false)); // AccessPolicy 无 ACTIVE 配置：A 级默认关闭（契约）
        if (!decision.allowed()) {
            // 未授权统一 404，不泄露存在性（05 §2；raiseIncident 信号由调用方服务记录）
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return decision;
    }

    /** 作品详情视图（元数据来自不可变公开版本）。 */
    public record WorkView(String versionId, String workId, String title, String synopsis,
                           String categoryId, java.util.List<String> tags, String grade) {
    }

    /** 章节正文视图（契约 Content：chapterId/versionId/title/body/grade）。 */
    public record ContentView(String chapterId, String versionId, String title, String body,
                              String grade) {
    }
}
