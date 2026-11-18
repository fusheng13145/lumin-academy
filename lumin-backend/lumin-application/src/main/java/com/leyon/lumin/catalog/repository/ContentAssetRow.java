package com.leyon.lumin.catalog.repository;

import java.time.LocalDateTime;

/** content_asset 表行记录（净化后正式资源；sha256 为净化字节的正式摘要）。 */
public record ContentAssetRow(
        String id,
        String purpose,
        String workId,
        String caseId,
        String contentType,
        Integer sizeBytes,
        String sha256,
        String objectKey,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
