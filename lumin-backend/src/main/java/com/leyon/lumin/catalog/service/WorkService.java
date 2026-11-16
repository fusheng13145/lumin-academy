package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.ChapterMapper;
import com.leyon.lumin.catalog.repository.WorkMapper;
import com.leyon.lumin.catalog.repository.WorkRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.web.IfMatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
}
