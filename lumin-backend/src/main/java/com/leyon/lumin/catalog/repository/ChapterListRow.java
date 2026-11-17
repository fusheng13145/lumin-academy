package com.leyon.lumin.catalog.repository;

/** 作者作品章节列表行（05 §3.2 listMyChapters；含可编辑草稿状态）。 */
public record ChapterListRow(
        String id,
        int sequenceNo,
        String businessState,
        String draftId,
        String draftState,
        String draftTitle,
        Long draftLockVersion) {
}
