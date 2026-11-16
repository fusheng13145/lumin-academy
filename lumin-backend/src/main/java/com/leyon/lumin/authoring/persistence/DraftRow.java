package com.leyon.lumin.authoring.persistence;

import java.time.LocalDateTime;

/**
 * draft 表行记录（docs/04 §4；JSON 列以字符串承载，MyBatis 驼峰映射）。
 * 领域校验（形状/长度/标签）由 authoring.domain 完成，持久化层不做业务判定。
 */
public record DraftRow(
        String id,
        String workId,
        String chapterId,
        String targetType,
        String baseVersionId,
        String title,
        String synopsis,
        String categoryId,
        String tags,
        String coverAssetId,
        String bodyText,
        String state,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
