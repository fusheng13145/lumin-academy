package com.leyon.lumin.engagement.repository;

import java.time.LocalDateTime;

/** 阅读进度行记录（docs/04 §5.1；U(user_id,chapter_id)，版本绑定）。 */
public record ReadingProgressRow(
        String id,
        String userId,
        String chapterId,
        String versionId,
        Integer positionBasisPoints,
        Integer readSeconds,
        LocalDateTime clientUpdatedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
