package com.leyon.lumin.configuration.repository;

import java.time.LocalDateTime;

/** config_revision 表行记录（docs/04 §5.1；payload 已冻结不可替换）。
 *  字段顺序必须与表列序一致（SELECT * 映射按列序对位——增量十九教训登记）。 */
public record ConfigRevisionRow(
        String id,
        String configKey,
        Long revision,
        Long baseRevision,
        Integer schemaVersion,
        String payload,
        String state,
        String reason,
        String requestedBy,
        String approvedBy,
        LocalDateTime effectiveAt,
        Integer rolloutBasisPoints,
        String rollbackOf,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
