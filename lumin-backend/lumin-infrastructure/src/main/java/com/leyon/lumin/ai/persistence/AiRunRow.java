package com.leyon.lumin.ai.persistence;

import java.time.LocalDateTime;

/** ai_run 的数据库行；与领域 record 分离，避免 MyBatis 绑定到领域对象。 */
public record AiRunRow(
        String id,
        String workId,
        String chapterId,
        String mode,
        String state,
        String instruction,
        String provider,
        String model,
        String memoryRevision,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
