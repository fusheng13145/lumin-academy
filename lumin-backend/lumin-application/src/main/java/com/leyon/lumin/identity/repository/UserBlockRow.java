package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/**
 * user_block 表行记录（docs/13 UGC-043 一阶段；V19）。
 * 字段顺序与表列序一致（SELECT * 位置读取）。
 */
public record UserBlockRow(
        String id,
        String blockerId,
        String blockedId,
        String reason,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy,
        Long lockVersion) {
}
