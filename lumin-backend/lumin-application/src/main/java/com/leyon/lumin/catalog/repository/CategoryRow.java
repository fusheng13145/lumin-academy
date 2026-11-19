package com.leyon.lumin.catalog.repository;

import java.time.LocalDateTime;

/** category 表行记录（docs/04 §4 catalog）。 */
public record CategoryRow(
        String id,
        String parentId,
        String name,
        Integer sortOrder,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion,
        LocalDateTime deletedAt) {
}
