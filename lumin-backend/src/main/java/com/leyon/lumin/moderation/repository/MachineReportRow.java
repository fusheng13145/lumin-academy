package com.leyon.lumin.moderation.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** machine_report 表行记录（docs/04 §5；不可变证据，无 deleted_at）。 */
public record MachineReportRow(
        String id,
        String caseId,
        String provider,
        String modelVersion,
        String materialSha256,
        String grade,
        Long policyVersion,
        String risk,
        String flags,
        String suggestedDecision,
        BigDecimal confidence,
        String evidenceRefs,
        Integer inputTokens,
        Integer outputTokens,
        Integer durationMs,
        LocalDateTime completedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
