package com.leyon.lumin.catalog.repository;

import java.time.LocalDateTime;

/** work 表行记录（docs/04 §4 catalog）。 */
public record WorkRow(
        String id,
        String authorId,
        String businessState,
        String priorState,
        String copyrightStatus,
        Integer nextChapterNo,
        LocalDateTime completedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
