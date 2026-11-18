package com.leyon.lumin.moderation.api.dto;

import java.time.LocalDateTime;

/** 案件视图（契约 CaseView 子集：id/workId/state/risk/priority/stage/revision）。 */
public record CaseView(
        String id,
        String workId,
        String chapterId,
        String state,
        String risk,
        String priority,
        int stage,
        String assigneeId,
        LocalDateTime responseDueAt,
        LocalDateTime completionDueAt,
        long revision) {
}
