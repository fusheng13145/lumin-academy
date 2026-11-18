package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.ChapterListRow;
import com.leyon.lumin.catalog.repository.ChapterMapper;
import com.leyon.lumin.catalog.repository.MyWorkRow;
import com.leyon.lumin.catalog.repository.WorkMapper;
import com.leyon.lumin.catalog.repository.WorkRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.web.IfMatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    public WorkService(WorkMapper workMapper,
                       com.leyon.lumin.catalog.repository.ChapterMapper chapterMapper) {
        this.workMapper = workMapper;
        this.chapterMapper = chapterMapper;
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

    /** 作者私有作品列表（05 §3.2 listMyWorks：含 DRAFT 与最新版本状态）。 */
    @Transactional(readOnly = true)
    public List<MyWorkRow> listMyWorks(String authorId) {
        return workMapper.listMyWorks(authorId);
    }

    /** 无归属校验的摘要读取（authoring 组装版本核验数据用；缺失返回 null）。 */
    public WorkSummary getWorkSummary(String workId) {
        WorkRow row = workId == null ? null : workMapper.findById(workId);
        return row == null ? null : new WorkSummary(row.id(), row.authorId(),
                row.copyrightStatus(), row.businessState(), row.lockVersion());
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

    /** 作者作品的章节列表（05 §3.2 listMyChapters：归属校验 + 可编辑草稿状态）。 */
    @Transactional(readOnly = true)
    public List<ChapterListRow> listMyChapters(String subject, String workId) {
        requireOwnedRow(workId, subject);
        return chapterMapper.listByWorkWithDraft(workId);
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
