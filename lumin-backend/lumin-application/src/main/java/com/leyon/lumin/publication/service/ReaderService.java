package com.leyon.lumin.publication.service;

import com.leyon.lumin.authoring.service.DraftLifecycleService;
import com.leyon.lumin.authoring.service.DraftLifecycleService.PublishedVersionRef;
import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.catalog.service.WorkService.WorkSummary;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.port.ActiveRestrictionsPort;
import com.leyon.lumin.foundation.port.CaseOwnershipPort;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
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

    private static final Logger log = LoggerFactory.getLogger(ReaderService.class);

    private final PublicationPointerMapper pointerMapper;
    private final PublicationWorkflowMapper workflowMapper;
    private final DraftLifecycleService draftLifecycleService;
    private final WorkService workService;
    private final ProfileService profileService;
    private final com.leyon.lumin.foundation.storage.ContentObjectStore objectStore;
    private final com.leyon.lumin.foundation.port.ActiveRestrictionsPort activeRestrictionsPort;
    private final com.leyon.lumin.foundation.port.CaseOwnershipPort caseOwnershipPort;
    private final String cursorKey;

    public ReaderService(PublicationPointerMapper pointerMapper,
                         PublicationWorkflowMapper workflowMapper,
                         DraftLifecycleService draftLifecycleService, WorkService workService,
                         ProfileService profileService,
                         com.leyon.lumin.foundation.storage.ContentObjectStore objectStore,
                         com.leyon.lumin.foundation.port.ActiveRestrictionsPort activeRestrictionsPort,
                         com.leyon.lumin.foundation.port.CaseOwnershipPort caseOwnershipPort,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${lumin.cursor.key}") String cursorKey) {
        this.pointerMapper = pointerMapper;
        this.workflowMapper = workflowMapper;
        this.draftLifecycleService = draftLifecycleService;
        this.workService = workService;
        this.profileService = profileService;
        this.objectStore = objectStore;
        this.activeRestrictionsPort = activeRestrictionsPort;
        this.caseOwnershipPort = caseOwnershipPort;
        this.cursorKey = cursorKey;
    }

    /** 读者可见的作品详情（05 §3.4 getWork：最新公开元数据，未授权统一 404）。 */
    public Optional<WorkView> getWork(String subject, String workId) {
        VisibilityDecision decision = decide(subject, TargetType.WORK, workId);
        if (!decision.allowed()) {
            return Optional.empty();
        }
        PublishedVersionRef version = draftLifecycleService
                .findPublishedVersion(decision.versionId());
        String authorId = caseOwnershipPort.authorIdOfWork(version.workId());
        String author = authorId == null ? null : profileService.findNickname(authorId);
        return Optional.of(new WorkView(version.versionId(), version.workId(),
                version.title(), author == null ? "" : author, version.synopsis(),
                version.categoryId(), version.tags(), version.grade()));
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
            log.warn("发布版本缺少正文对象，chapterId={}, versionId={}",
                    version.chapterId(), version.versionId());
            return Optional.empty();
        }
        String body = new String(objectStore.read(version.objectKey()),
                java.nio.charset.StandardCharsets.UTF_8);
        return Optional.of(new ContentView(version.chapterId(), version.versionId(),
                version.title(), body, version.grade()));
    }

    /** 作品列表页（UGC-009：签名字游标——授权过滤 SQL 化，跨页无漏重、隐藏数不泄露）。 */
    public record WorksPage(java.util.List<WorkListItem> items, String nextCursor) {
    }

    /**
     * 读者作品列表（UGC-009；05 §2 游标口径）：授权过滤在 SQL（等级口径与
     * AgePolicy.canReadPublic 同源等价）；游标绑定主体 + 过滤摘要、HMAC 签名、
     * 15 分钟过期；响应 data 为数组（契约不变），nextCursor 经响应头传递。
     */
    public WorksPage listWorks(String subject, String cursorToken, Integer limitParam) {
        ErrorCode limitCheck = com.leyon.lumin.foundation.cursor.CursorCodec
                .validateLimit(limitParam);
        if (limitCheck != ErrorCode.OK) {
            throw new LuminBusinessException(limitCheck);
        }
        int limit = com.leyon.lumin.foundation.cursor.CursorCodec.resolveLimit(limitParam);
        ProfileSummary requester = profileService.requireProfile(subject);
        AgeBand band = AgeBand.valueOf(requester.effectiveAgeBand());
        // C4 读带按 C1（AgeBand.readingBand 同源口径）
        String bandVar = (band == AgeBand.C4 ? AgeBand.C1 : band).name();
        String filterDigest = com.leyon.lumin.foundation.cursor.CursorCodec
                .filterDigest("list=works;band=" + bandVar);
        java.time.LocalDateTime now = java.time.LocalDateTime.now(ZoneOffset.UTC);
        java.time.LocalDateTime cursorAt = null;
        String cursorId = null;
        if (cursorToken != null && !cursorToken.isBlank()) {
            var decoded = com.leyon.lumin.foundation.cursor.CursorCodec.decode(cursorToken,
                    cursorKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.time.Instant.now(), filterDigest);
            if (decoded.code() != ErrorCode.OK) {
                // 失效/过期/篡改游标 → 400（05 §2；客户端应从第一页重新分页）
                throw new LuminBusinessException(decoded.code());
            }
            if (!decoded.payload().subjectId().toString().equals(subject)) {
                // 主体作用域绑定：他人游标不可用
                throw new LuminBusinessException(ErrorCode.P1001);
            }
            cursorAt = java.time.LocalDateTime.parse(decoded.payload().sortKeyValue());
            cursorId = decoded.payload().lastId().toString();
        }
        var rows = pointerMapper.listVisibleWorks(bandVar, cursorAt, cursorId, limit + 1);
        boolean hasMore = rows.size() > limit;
        var page = rows.subList(0, Math.min(rows.size(), limit));
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            var last = page.get(page.size() - 1);
            nextCursor = com.leyon.lumin.foundation.cursor.CursorCodec.encode(
                    new com.leyon.lumin.foundation.cursor.CursorCodec.Payload(
                            last.publishedAt().toString(), UUID.fromString(last.pointerId()),
                            filterDigest, UUID.fromString(subject),
                            now.toInstant(ZoneOffset.UTC)),
                    cursorKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        var items = page.stream()
                .map(row -> new WorkListItem(row.versionId(), row.workId(), row.title(),
                        row.author(), row.grade(), row.chapterId()))
                .toList();
        return new WorksPage(items, nextCursor);
    }

    /** 读者可见的章节目录（05 §3.4 listChapters：SQL 等价过滤，sequenceNo 排序）。 */
    public java.util.List<ChapterEntry> listChapters(String subject, String workId) {
        decide(subject, TargetType.WORK, workId);
        return pointerMapper.listPublishedChapters(workId).stream()
                .map(c -> new ChapterEntry(c.id(), c.sequenceNo(), c.businessState()))
                .toList();
    }

    /** 章节目录项。 */
    public record ChapterEntry(String id, int sequenceNo, String businessState) {
    }

    /** 作品列表项（书城卡片：含作者昵称与首章 ID）。 */
    public record WorkListItem(String versionId, String workId, String title, String author,
                               String grade, String chapterId) {
    }

    /** 章节可读裁决桥（foundation.ChapterAccessPort；engagement 进度域使用）：
     *  可读返回当前公开指针所指版本 ID（进度版本绑定），否则 null（404 语义）。 */
    public String readableVersion(String subject, String chapterId) {
        try {
            return decide(subject, TargetType.CHAPTER, chapterId).versionId();
        } catch (LuminBusinessException denied) {
            return null;
        }
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
        boolean chapterTarget = targetType == TargetType.CHAPTER;
        String versionTargetId = chapterTarget ? version.chapterId() : version.workId();
        // CHAPTER 目标用章节自身业务态；WORK 目标用作品业务态（03 §3.1 步骤 2/6）
        String targetStateRaw = chapterTarget
                ? workService.chapterBusinessState(versionTargetId)
                : (work == null ? null : work.businessState());
        VisibilityDecision decision = VisibilityPolicy.decide(new VisibilityInput(
                AgeBand.valueOf(requester.effectiveAgeBand()),
                AccountStatus.valueOf(requester.accountStatus()),
                targetType, targetId,
                targetStateRaw == null ? null
                        : com.leyon.lumin.foundation.values.BusinessState.valueOf(targetStateRaw),
                work == null ? null : com.leyon.lumin.foundation.values.BusinessState
                        .valueOf(work.businessState()),
                activeRestrictionsPort.activeKinds(targetType.name(), version.workId(),
                        version.chapterId()), // 生效治理限制（governance 端口，INV-03）
                version.versionId(), VersionState.valueOf(version.state()),
                version.grade() == null ? null : Grade.valueOf(version.grade()),
                workflow == null ? null : workflow.id(),
                workflow == null ? null : WorkflowState.valueOf(workflow.state()),
                workflow == null ? null : workflow.versionId(),
                version.policyVersion(), version.policyVersion(),
                pointer.versionId(), targetId,
                versionTargetId,
                true, true, true,
                false, false)); // AccessPolicy 无 ACTIVE 配置：A 级默认关闭（契约）
        if (!decision.allowed()) {
            // 未授权统一 404，不泄露存在性（05 §2；raiseIncident 信号由调用方服务记录）
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return decision;
    }

    /** 作品详情视图（元数据来自不可变公开版本；作者昵称为公开档案字段）。 */
    public record WorkView(String versionId, String workId, String title, String author,
                           String synopsis, String categoryId, java.util.List<String> tags,
                           String grade) {
    }

    /** 章节正文视图（契约 Content：chapterId/versionId/title/body/grade）。 */
    public record ContentView(String chapterId, String versionId, String title, String body,
                              String grade) {
    }
}
