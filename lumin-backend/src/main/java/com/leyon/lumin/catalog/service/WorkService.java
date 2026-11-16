package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.WorkMapper;
import com.leyon.lumin.catalog.repository.WorkRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
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
                              String businessState) {
    }

    /** 归属校验：不存在 / 越权统一 404 B2004（05 §2：相同文案，避免枚举）。 */
    public WorkSummary requireOwnedWork(String workId, String subjectId) {
        WorkRow row = workId == null ? null : workMapper.findById(workId);
        if (row == null || !row.authorId().equals(subjectId)) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return new WorkSummary(row.id(), row.authorId(), row.copyrightStatus(),
                row.businessState());
    }

    /** 章节归属校验：chapter 必须属于 work（04 §2；归属 ID 从权威记录读取，INV-07）。 */
    public void requireChapterInWork(String chapterId, String workId) {
        String chapterWorkId = chapterId == null ? null : chapterMapper.findWorkIdById(chapterId);
        if (chapterWorkId == null || !chapterWorkId.equals(workId)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
    }
}
