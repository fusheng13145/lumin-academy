package com.leyon.lumin.moderation.api.dto;

import java.time.LocalDateTime;

/** 契约 ReviewContent：当前有效领取人的完整冻结材料（禁止缓存/导出）。 */
public record ReviewContentView(
        String caseId,
        String title,
        String text,
        String watermark,
        LocalDateTime viewedAt) {
}
