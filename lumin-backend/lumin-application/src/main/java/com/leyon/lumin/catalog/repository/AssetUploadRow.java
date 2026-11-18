package com.leyon.lumin.catalog.repository;

import java.time.LocalDateTime;

/** asset_upload 表行记录（docs/13 UGC-010；上传会话 ID ≠ 正式资源 ID）。 */
public record AssetUploadRow(
        String id,
        String purpose,
        String workId,
        String caseId,
        String contentType,
        Integer sizeBytes,
        String sha256Raw,
        String objectKey,
        String state,
        String assetId,
        String failureReason,
        String requestedBy,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
