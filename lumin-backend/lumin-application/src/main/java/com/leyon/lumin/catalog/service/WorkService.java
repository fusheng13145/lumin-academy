package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.ChapterListRow;
import com.leyon.lumin.catalog.repository.ChapterMapper;
import com.leyon.lumin.catalog.repository.MyWorkRow;
import com.leyon.lumin.catalog.repository.WorkMapper;
import com.leyon.lumin.catalog.repository.WorkRow;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.web.IfMatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 作品服务（catalog 域所有权：02 §3.1 work/chapter 唯一所有权归 catalog）。
 * authoring 经本服务校验作品归属与版权状态（02 §3.1 允许边 authoring→catalog，
 * 跨模块禁止直接 Repository 访问）。
 */
@Service
public class WorkService {

    private final WorkMapper workMapper;
    private final com.leyon.lumin.catalog.repository.ChapterMapper chapterMapper;
    private final String cursorKey;

    public WorkService(WorkMapper workMapper,
                       com.leyon.lumin.catalog.repository.ChapterMapper chapterMapper,
                       @Value("${lumin.cursor.key}") String cursorKey) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
        this.cursorKey = cursorKey;
    }

    /** 新作品：DRAFT + 版权 VALID，不直接公开元数据（05 §3.2 createWork）。 */
    @Transactional
    public String createWork(String authorId, LocalDateTime now) {
        WorkRow row = new WorkRow(UUID.randomUUID().toString(), authorId, "DRAFT", null,
                "VALID", 1, null, now, now, authorId, authorId, 1L);
        workMapper.insert(row);
        return row.id();
    }

    /** 跨模块可见的作品摘要（不泄漏 catalog.repository 行类型，02 §3.1 禁跨模块 Repository 访问）。 */
    public record WorkSummary(String id, String authorId, String copyrightStatus,
                              String businessState, long lockVersion) {
    }

    /** 归属校验：不存在 / 越权统一 404 B2004（05 §2：相同文案，避免枚举）。 */
    public WorkSummary requireOwnedWork(String workId, String subjectId) {
        WorkRow row = workId == null ? null : workMapper.findById(workId);
        if (row == null || !row.authorId().equals(subjectId)) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return new WorkSummary(row.id(), row.authorId(), row.copyrightStatus(),
                row.businessState(), row.lockVersion());
    }

    /** 契约 AuthorWorkView（05 §3.2；state 含治理态 REMOVED/TERMINATED）。 */
    public record AuthorWorkView(String id, String state, String title, String draftId,
                                 String draftState, String publishedVersionId, long revision) {
    }

    /** 契约 AuthorChapterView（05 §3.2；state 含治理态 REMOVED/TERMINATED）。 */
    public record AuthorChapterView(String id, String workId, int sequenceNo, String state,
                                    String title, String draftId, String draftState,
                                    String publishedVersionId, long revision) {
    }

    public record Page<T>(List<T> items, String nextCursor) {
    }

    /** 作者私有作品列表（05 §3.2 listMyWorks：created_at,id 倒序签名游标）。 */
    @Transactional(readOnly = true)
    public Page<AuthorWorkView> listMyWorks(String subject, String cursorToken, Integer limitParam) {
        requireLimit(limitParam);
        int limit = CursorCodec.resolveLimit(limitParam);
        String filterDigest = CursorCodec.filterDigest("list=my-works;viewer=" + subject);
        LocalDateTime cursorAt = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
        UUID cursorId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        if (cursorToken != null && !cursorToken.isBlank()) {
            CursorCodec.DecodedCursor decoded = CursorCodec.decode(cursorToken,
                    cursorKey.getBytes(StandardCharsets.UTF_8), Instant.now(), filterDigest);
            requireCursor(decoded, subject);
            cursorAt = LocalDateTime.parse(decoded.payload().sortKeyValue());
            cursorId = decoded.payload().lastId();
        }
        List<MyWorkRow> rows = workMapper.listMyWorks(subject, cursorAt,
                cursorId.toString(), limit + 1);
        boolean hasNext = rows.size() > limit;
        List<MyWorkRow> pageRows = hasNext ? rows.subList(0, limit) : rows;
        List<AuthorWorkView> items = pageRows.stream()
                .map(row -> new AuthorWorkView(row.id(), row.businessState(),
                        row.latestTitle() != null ? row.latestTitle() : row.draftTitle(),
                        row.draftId(), row.draftState(), row.publishedVersionId(),
                        row.lockVersion() == null ? 1L : row.lockVersion()))
                .toList();
        String nextCursor = null;
        if (hasNext) {
            MyWorkRow last = pageRows.get(pageRows.size() - 1);
            nextCursor = CursorCodec.encode(new CursorCodec.Payload(
                    last.createdAt().toString(), UUID.fromString(last.id()), filterDigest,
                    UUID.fromString(subject), Instant.now()),
                    cursorKey.getBytes(StandardCharsets.UTF_8));
        }
        return new Page<>(items, nextCursor);
    }

    /** 作者私有作品详情（05 §3.2 getMyWork；不存在/越权统一 404）。 */
    public record MyWorkDetail(String id, String state, String title, String draftId,
                               String draftState, String publishedVersionId, long revision) {
    }

    @Transactional(readOnly = true)
    public MyWorkDetail getMyWork(String authorId, String workId) {
        com.leyon.lumin.catalog.repository.MyWorkDetailRow row =
                workId == null ? null : workMapper.findMyWorkDetail(workId, authorId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return new MyWorkDetail(row.id(), row.businessState(), row.title(), row.draftId(),
                row.draftState(), row.publishedVersionId(),
                row.lockVersion() == null ? 1L : row.lockVersion());
    }

    /** 作品下全部既有章节是否均已 ACTIVE（完结前提；空作品视为满足）。 */
    @Transactional(readOnly = true)
    public boolean allChaptersPublished(String workId) {
        return chapterMapper.countUnpublishedByWork(workId) == 0;
    }

    /**
     * 作者生命周期业务态迁移（03 §2.1 COMPLETE_REQUESTED / RESUME_SERIAL）：
     * 调用方已用状态机裁决，本方法只做归属、乐观锁与持久化。
     */
    @Transactional
    public long changeBusinessState(String subject, String workId, String targetState,
                                    boolean complete, long expectedLockVersion,
                                    LocalDateTime now) {
        WorkRow row = workId == null ? null : workMapper.findById(workId);
        if (row == null || !row.authorId().equals(subject)) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (row.lockVersion() != expectedLockVersion) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        int rows = workMapper.updateBusinessStateIfLock(workId, targetState,
                complete ? row.businessState() : null,
                complete ? now : null, subject, now, row.businessState(), expectedLockVersion);
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        return expectedLockVersion + 1;
    }

    /** 无归属校验的摘要读取（authoring 组装版本核验数据用；缺失返回 null）。 */
    public WorkSummary getWorkSummary(String workId) {
        WorkRow row = workId == null ? null : workMapper.findById(workId);
        return row == null ? null : new WorkSummary(row.id(), row.authorId(),
                row.copyrightStatus(), row.businessState(), row.lockVersion());
    }

    /** 批量摘要读取（审核队列年龄带组装用；缺失作品不出现在 Map 中）。 */
    public java.util.Map<String, WorkSummary> getWorkSummaries(List<String> workIds) {
        if (workIds == null || workIds.isEmpty()) {
            return java.util.Map.of();
        }
        return workMapper.findByIds(workIds.stream().filter(java.util.Objects::nonNull)
                        .distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(WorkRow::id,
                        row -> new WorkSummary(row.id(), row.authorId(),
                                row.copyrightStatus(), row.businessState(), row.lockVersion())));
    }

    /**
     * 新增章节（05 §3.2 createChapter）：If-Match 匹配作品 lock_version，原子取号；
     * 完结/下架/终止作品禁止新章（B2001），并发取号由乐观锁 + U(work_id,sequence_no) 兜底。
     */
    @Transactional
    public String createChapter(String subject, String workId, String ifMatch,
                                LocalDateTime now) {
        WorkRow work = requireOwnedRow(workId, subject);
        long expected = IfMatch.requireValid(ifMatch);
        // 03 §2.1：完结/下架/终止作品禁止新增章节（先于乐观锁判定，区分 B2001/B2002）
        if (!"DRAFT".equals(work.businessState()) && !"ACTIVE".equals(work.businessState())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        if (expected != work.lockVersion()) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        int rows = workMapper.allocateChapterNo(workId, expected, subject, now);
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        Integer next = workMapper.selectNextChapterNo(workId);
        int sequenceNo = (next == null ? 1 : next) - 1;
        String chapterId = UUID.randomUUID().toString();
        chapterMapper.insert(chapterId, workId, sequenceNo, subject, now);
        return chapterId;
    }

    /** 内部归属校验（同模块内保留行访问）。 */
    private WorkRow requireOwnedRow(String workId, String subjectId) {
        WorkRow row = workId == null ? null : workMapper.findById(workId);
        if (row == null || !row.authorId().equals(subjectId)) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return row;
    }

    /** 章节归属校验：chapter 必须属于 work（04 §2；归属 ID 从权威记录读取，INV-07）。 */
    public void requireChapterInWork(String chapterId, String workId) {
        String chapterWorkId = chapterId == null ? null : chapterMapper.findWorkIdById(chapterId);
        if (chapterWorkId == null || !chapterWorkId.equals(workId)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
    }

    /** 作者作品的章节列表（05 §3.2 listMyChapters：sequence_no,id 升序签名游标）。 */
    @Transactional(readOnly = true)
    public Page<AuthorChapterView> listMyChapters(String subject, String workId,
                                                  String cursorToken, Integer limitParam) {
        requireLimit(limitParam);
        requireOwnedRow(workId, subject);
        int limit = CursorCodec.resolveLimit(limitParam);
        String filterDigest = CursorCodec.filterDigest("list=my-chapters;work=" + workId
                + ";viewer=" + subject);
        int cursorSeq = 0;
        UUID cursorId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        if (cursorToken != null && !cursorToken.isBlank()) {
            CursorCodec.DecodedCursor decoded = CursorCodec.decode(cursorToken,
                    cursorKey.getBytes(StandardCharsets.UTF_8), Instant.now(), filterDigest);
            requireCursor(decoded, subject);
            cursorSeq = Integer.parseInt(decoded.payload().sortKeyValue());
            cursorId = decoded.payload().lastId();
        }
        List<ChapterListRow> rows = chapterMapper.listByWorkWithDraft(workId, cursorSeq,
                cursorId.toString(), limit + 1);
        boolean hasNext = rows.size() > limit;
        List<ChapterListRow> pageRows = hasNext ? rows.subList(0, limit) : rows;
        List<AuthorChapterView> items = pageRows.stream()
                .map(row -> new AuthorChapterView(row.id(), row.workId(), row.sequenceNo(),
                        row.businessState(), row.title(), row.draftId(), row.draftState(),
                        row.publishedVersionId(),
                        row.lockVersion() == null ? 1L : row.lockVersion()))
                .toList();
        String nextCursor = null;
        if (hasNext) {
            ChapterListRow last = pageRows.get(pageRows.size() - 1);
            nextCursor = CursorCodec.encode(new CursorCodec.Payload(
                    Integer.toString(last.sequenceNo()), UUID.fromString(last.id()), filterDigest,
                    UUID.fromString(subject), Instant.now()),
                    cursorKey.getBytes(StandardCharsets.UTF_8));
        }
        return new Page<>(items, nextCursor);
    }

    /** 显式 limit 非法 → 400/P1001（05 §2；不静默钳制）。 */
    private void requireLimit(Integer limitParam) {
        if (CursorCodec.validateLimit(limitParam) != ErrorCode.OK) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
    }

    /** 游标必须签名有效、未过期且属于当前主体。 */
    private void requireCursor(CursorCodec.DecodedCursor decoded, String subject) {
        if (decoded.code() != ErrorCode.OK
                || !decoded.payload().subjectId().toString().equals(subject)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
    }

    /** 首版发布成功：DRAFT →(FIRST_VERSION_PUBLISHED)→ ACTIVE（03 §2.1，SYSTEM）。
     *  已发布（非 DRAFT）作品发布修订版不触发业务态迁移（状态机无此事件行）——
     *  否则迁移拒绝将回滚整个发布事务（含指针交换），修订永远无法发布。 */
    @Transactional
    public void markFirstVersionPublished(String workId, String operator, LocalDateTime now) {
        WorkRow work = workMapper.findById(workId);
        if (work == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (!"DRAFT".equals(work.businessState())) {
            return;
        }
        var transition = com.leyon.lumin.publication.domain.PublicationStateMachine.business(
                com.leyon.lumin.foundation.values.BusinessState.valueOf(work.businessState()),
                com.leyon.lumin.foundation.values.PublicationEvent.FIRST_VERSION_PUBLISHED,
                new com.leyon.lumin.publication.domain.TransitionContext(
                        com.leyon.lumin.foundation.values.TargetType.WORK,
                        true, true, true, null, true, true, true, true, true, true,
                        true, true, true, false, true, false));
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        workMapper.updateBusinessState(workId, transition.toState().name(),
                work.businessState(), null, operator, now, work.businessState());
    }

    /** 章节业务态读取（读者链 CHAPTER 目标裁决输入；无行返回 null）。 */
    @Transactional(readOnly = true)
    public String chapterBusinessState(String chapterId) {
        com.leyon.lumin.catalog.repository.ChapterStateRow row =
                chapterMapper.findStateById(chapterId);
        return row == null ? null : row.businessState();
    }

    /** 生效下架（03 §2.1）：作品业务态 →(TAKEDOWN_EFFECTIVE)→ REMOVED，保存前态。 */
    @Transactional
    public void markTakedown(String workId, String operator, LocalDateTime now) {
        WorkRow work = workMapper.findById(workId);
        if (work == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        var transition = com.leyon.lumin.publication.domain.PublicationStateMachine.business(
                com.leyon.lumin.foundation.values.BusinessState.valueOf(work.businessState()),
                com.leyon.lumin.foundation.values.PublicationEvent.TAKEDOWN_EFFECTIVE,
                new com.leyon.lumin.publication.domain.TransitionContext(
                        com.leyon.lumin.foundation.values.TargetType.WORK,
                        true, true, true, null, true, true, true, true, true, true,
                        true, true, true, false, true, false));
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        workMapper.updateBusinessState(workId, transition.toState().name(),
                work.businessState(), null, operator, now, work.businessState());
    }

    /** 改判恢复（03 §2.1）：REMOVED →(TEMPORARY_APPEAL_UPHELD)→ 行内记录的前态；
     *  前提核验（限制全解除/指针在）由端口实现方完成后调用。 */
    @Transactional
    public void markRestored(String workId, String operator, LocalDateTime now) {
        WorkRow work = workMapper.findById(workId);
        if (work == null || !"REMOVED".equals(work.businessState())
                || work.priorState() == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        var transition = com.leyon.lumin.publication.domain.PublicationStateMachine.business(
                com.leyon.lumin.foundation.values.BusinessState.REMOVED,
                com.leyon.lumin.foundation.values.PublicationEvent.TEMPORARY_APPEAL_UPHELD,
                new com.leyon.lumin.publication.domain.TransitionContext(
                        com.leyon.lumin.foundation.values.TargetType.WORK,
                        true, true, true, null, true, true, true, true, true, true,
                        true, true, true, false, true, false));
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        workMapper.updateBusinessState(workId, work.priorState(), "REMOVED", null,
                operator, now, "REMOVED");
    }

    /** 生效下架（章节）：业务态 → REMOVED，保存前态（03 §2.1 作品与章节业务态同表）。 */
    @Transactional
    public void markChapterTakedown(String chapterId, String operator, LocalDateTime now) {
        com.leyon.lumin.catalog.repository.ChapterStateRow chapter =
                chapterMapper.findStateById(chapterId);
        if (chapter == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        var transition = com.leyon.lumin.publication.domain.PublicationStateMachine.business(
                com.leyon.lumin.foundation.values.BusinessState.valueOf(chapter.businessState()),
                com.leyon.lumin.foundation.values.PublicationEvent.TAKEDOWN_EFFECTIVE,
                new com.leyon.lumin.publication.domain.TransitionContext(
                        com.leyon.lumin.foundation.values.TargetType.CHAPTER,
                        true, true, true, null, true, true, true, true, true, true,
                        true, true, true, false, true, false));
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        chapterMapper.updateBusinessState(chapterId, transition.toState().name(),
                chapter.businessState(), operator, now, chapter.businessState());
    }

    /** 改判恢复（章节）：REMOVED → 记录的前态（前提核验由端口实现方完成）。 */
    @Transactional
    public void markChapterRestored(String chapterId, String operator, LocalDateTime now) {
        com.leyon.lumin.catalog.repository.ChapterStateRow chapter =
                chapterMapper.findStateById(chapterId);
        if (chapter == null || !"REMOVED".equals(chapter.businessState())
                || chapter.priorState() == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        var transition = com.leyon.lumin.publication.domain.PublicationStateMachine.business(
                com.leyon.lumin.foundation.values.BusinessState.REMOVED,
                com.leyon.lumin.foundation.values.PublicationEvent.TEMPORARY_APPEAL_UPHELD,
                new com.leyon.lumin.publication.domain.TransitionContext(
                        com.leyon.lumin.foundation.values.TargetType.CHAPTER,
                        true, true, true, null, true, true, true, true, true, true,
                        true, true, true, false, true, false));
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        chapterMapper.updateBusinessState(chapterId, chapter.priorState(), "REMOVED",
                operator, now, "REMOVED");
    }

    /** 章节首版发布成功：DRAFT →(FIRST_VERSION_PUBLISHED)→ ACTIVE（03 §2.1，SYSTEM）。
     *  已发布章节发布修订版不触发业务态迁移（同上，防回滚吞指针交换）。 */
    @Transactional
    public void markChapterPublished(String chapterId, String operator, LocalDateTime now) {
        com.leyon.lumin.catalog.repository.ChapterStateRow chapter =
                chapterMapper.findStateById(chapterId);
        if (chapter == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (!"DRAFT".equals(chapter.businessState())) {
            return;
        }
        var transition = com.leyon.lumin.publication.domain.PublicationStateMachine.business(
                com.leyon.lumin.foundation.values.BusinessState.valueOf(chapter.businessState()),
                com.leyon.lumin.foundation.values.PublicationEvent.FIRST_VERSION_PUBLISHED,
                new com.leyon.lumin.publication.domain.TransitionContext(
                        com.leyon.lumin.foundation.values.TargetType.CHAPTER,
                        true, true, true, null, true, true, true, true, true, true,
                        true, true, true, false, true, false));
        if (!transition.accepted()) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        chapterMapper.updateBusinessState(chapterId, transition.toState().name(),
                chapter.businessState(), operator, now, chapter.businessState());
    }
}
