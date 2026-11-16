package com.leyon.lumin.publication.persistence;

import java.time.LocalDateTime;

/** publication_pointer 表行记录（docs/04 §4；INV-02：每目标唯一、仅指向 APPROVED 版本）。 */
public record PublicationPointerRow(
        String id,
        String targetType,
        String targetId,
        String versionId,
        LocalDateTime publishedAt,
        Long publicationRevision,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
