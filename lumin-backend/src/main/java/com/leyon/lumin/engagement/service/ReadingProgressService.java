package com.leyon.lumin.engagement.service;

import com.leyon.lumin.engagement.domain.ReadingProgressValidator;
import com.leyon.lumin.engagement.repository.ReadingProgressMapper;
import com.leyon.lumin.engagement.repository.ReadingProgressRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.port.ChapterAccessPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 阅读进度应用服务（05 §3.4 saveProgress / getProgress；T29）：
 * 本人且可读该章节才可写（可读性经 foundation 端口由 publication 裁决）；
 * 边界校验经 {@link ReadingProgressValidator} 纯函数；进度行版本绑定——
 * 章节发布新版本后保存即重绑至当前公开版本并返回 versionChanged 标记
 * （04 §5.1「版本变化提示而不跨版本乱套位置」：V1 位置从不被呈现为 V2 位置，
 * GET 暴露 versionId 供 UI 提示版本变更）。
 */
@Service
public class ReadingProgressService {

    /** 首次写入的 If-Match 值（05 §3.4 契约：首次写 If-Match="0"）。 */
    public static final long FIRST_WRITE_LOCK = 0L;

    private final ReadingProgressMapper progressMapper;
    private final ChapterAccessPort chapterAccessPort;

    public ReadingProgressService(ReadingProgressMapper progressMapper,
                                  ChapterAccessPort chapterAccessPort) {
        this.progressMapper = progressMapper;
        this.chapterAccessPort = chapterAccessPort;
    }

    /** 保存进度（本人 + 可读 + 边界合法 + 乐观锁）；返回当前 ETag（行 lock_version）。 */
    @Transactional
    public SaveResult save(String subject, String chapterId, String ifMatch,
                           Integer positionBasisPoints, Integer readSeconds,
                           Instant clientUpdatedAt) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long expectedLock = com.leyon.lumin.foundation.web.IfMatch.requireValid(ifMatch);
        Instant serverNow = Instant.now();
        ErrorCode violation = ReadingProgressValidator.validate(
                positionBasisPoints, readSeconds, clientUpdatedAt, serverNow);
        if (violation != ErrorCode.OK) {
            throw new LuminBusinessException(violation);
        }
        // 本人且可读该章节（未授权统一 404，不泄露存在性）；绑定当前公开版本
        String currentVersionId = chapterAccessPort.readableVersion(subject, chapterId);
        if (currentVersionId == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        ReadingProgressRow existing = progressMapper.find(subject, chapterId);
        LocalDateTime clientTime = LocalDateTime.ofInstant(clientUpdatedAt, ZoneOffset.UTC);
        if (existing == null) {
            if (expectedLock != FIRST_WRITE_LOCK) {
                throw new LuminBusinessException(ErrorCode.B2002);
            }
            ReadingProgressRow row = new ReadingProgressRow(UUID.randomUUID().toString(),
                    subject, chapterId, currentVersionId, positionBasisPoints, readSeconds,
                    clientTime, now, now, subject, subject, 1L);
            progressMapper.insert(row);
            return new SaveResult(1L, false);
        }
        if (expectedLock != existing.lockVersion()) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        boolean versionChanged = !currentVersionId.equals(existing.versionId());
        int rows = progressMapper.update(subject, chapterId, currentVersionId,
                positionBasisPoints, readSeconds, clientTime, subject, now, expectedLock);
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        return new SaveResult(existing.lockVersion() + 1, versionChanged);
    }

    /** 本人进度（404 语义：无行即未读过；越权由按主体查询天然隔离）。 */
    @Transactional(readOnly = true)
    public ReadingProgressRow get(String subject, String chapterId) {
        ReadingProgressRow row = progressMapper.find(subject, chapterId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return row;
    }

    /** 保存结果：新 ETag（lock_version）+ 是否发生版本重绑（UI 版本变更提示依据）。 */
    public record SaveResult(long etag, boolean versionChanged) {
    }
}
