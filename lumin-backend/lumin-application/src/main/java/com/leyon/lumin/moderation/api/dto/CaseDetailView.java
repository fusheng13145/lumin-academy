package com.leyon.lumin.moderation.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 契约 CaseView：案件详情脱敏元数据 + 完整裁决记录。 */
public record CaseDetailView(
        String id,
        String workId,
        String versionId,
        String state,
        String risk,
        String priority,
        String authorAgeBand,
        LocalDateTime completionDueAt,
        LocalDateTime leaseExpiresAt,
        long revision,
        List<DecisionItemView> decisions) {
}
