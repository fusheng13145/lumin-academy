package com.leyon.lumin.governance.repository;

import java.time.LocalDateTime;

/** report 表行记录（docs/04 §5 governance；举报关联 moderation 建案，case_id 由端口回填）。 */
public record ReportRow(
        String id,
        String reporterId,
        String workId,
        String chapterId,
        String reasonCode,
        String description,
        String caseId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion,
        LocalDateTime deletedAt) {
}
