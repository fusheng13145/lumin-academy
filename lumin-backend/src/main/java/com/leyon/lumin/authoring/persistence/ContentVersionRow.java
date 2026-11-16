package com.leyon.lumin.authoring.persistence;

import java.time.LocalDateTime;

/** content_version 表行记录（docs/04 §4；内容不可变，状态/等级由状态机字段更新并审计）。 */
public record ContentVersionRow(
        String id,
        String workId,
        String chapterId,
        String targetType,
        Long revisionNo,
        String sourceDraftId,
        String title,
        String synopsis,
        String categoryId,
        String tags,
        String coverAssetId,
        String objectKey,
        String bodySha256,
        String materialSnapshot,
        String materialSha256,
        Integer byteLength,
        String state,
        String grade,
        Long policyVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
