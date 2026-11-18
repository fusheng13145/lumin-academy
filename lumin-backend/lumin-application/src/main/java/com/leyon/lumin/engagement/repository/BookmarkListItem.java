package com.leyon.lumin.engagement.repository;

import java.time.LocalDateTime;

/** 书架列表行（UGC-035：含可读状态口径字段；下架/无公开指针 → 不可读）。 */
public record BookmarkListItem(
        String bookmarkId,
        String workId,
        LocalDateTime createdAt,
        String workState,
        String versionId,
        String title,
        String grade,
        String chapterId,
        String author) {

    /** 可读判定（与 ReaderService SQL 等价口径：业务态 + 等级带由前端灰显）。 */
    public boolean readable() {
        return versionId != null
                && ("ACTIVE".equals(workState) || "COMPLETED".equals(workState));
    }
}
