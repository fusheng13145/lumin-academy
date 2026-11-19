package com.leyon.lumin.catalog.repository;

import java.time.LocalDateTime;

/**
 * curation_item 表行记录（docs/13 UGC-039；V18）。
 * 字段顺序与表列序一致（SELECT * 位置读取）。
 */
public record CurationItemRow(
        String id,
        Integer ruleVersion,
        String workId,
        String exposureReason,
        String note,
        String state,
        Integer positionRank,
        String proposedBy,
        String reviewedBy,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy,
        Long lockVersion) {
}
