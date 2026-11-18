package com.leyon.lumin.catalog.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.TargetType;

import java.util.Objects;
import java.util.UUID;

/**
 * 发布目标引用（04 §2 / §4：WORK 时 chapter_id 为空，CHAPTER 时非空且必须属于 work_id）。
 *
 * <p>作品元数据也走内容版本审核——目标为 WORK 的版本审核标题/简介/分类/标签/封面，
 * 不允许绕过审核直改已发布元数据（04 §2 末段、05 §3.2）。
 */
public record TargetRef(TargetType targetType, UUID workId, UUID chapterId) {

    /** 结构校验：返回 {@link ErrorCode#OK} 表示合法，否则为拒绝错误码（400/P1001）。 */
    public ErrorCode validate() {
        if (targetType == null || workId == null) {
            return ErrorCode.P1001;
        }
        if (targetType == TargetType.WORK && chapterId != null) {
            return ErrorCode.P1001;
        }
        if (targetType == TargetType.CHAPTER && chapterId == null) {
            return ErrorCode.P1001;
        }
        return ErrorCode.OK;
    }

    /**
     * 章节归属校验（仅 CHAPTER 有意义）：{@code chapterWorkId} 为章节在
     * chapter 表中的 work_id，由服务层从权威记录读取（INV-07），不得取自请求体。
     */
    public ErrorCode validateChapterBelongsTo(UUID chapterWorkId) {
        if (validate() != ErrorCode.OK || targetType != TargetType.CHAPTER) {
            return ErrorCode.P1001;
        }
        return chapterWorkId != null && Objects.equals(workId, chapterWorkId)
                ? ErrorCode.OK : ErrorCode.P1001;
    }

    /** 目标实体 ID（04 §4：WORK → work_id；CHAPTER → chapter_id）。 */
    public UUID targetId() {
        return targetType == TargetType.CHAPTER ? chapterId : workId;
    }
}
