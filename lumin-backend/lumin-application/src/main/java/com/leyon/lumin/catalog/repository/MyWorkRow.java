package com.leyon.lumin.catalog.repository;

/** 作者私有作品列表行（05 §3.2 listMyWorks；含可编辑草稿与最新版本摘要）。 */
public record MyWorkRow(
        String id,
        String businessState,
        String copyrightStatus,
        Long lockVersion,
        String draftId,
        String draftState,
        String draftTitle,
        Long draftLockVersion,
        String latestTitle,
        String latestVersionState,
        String latestGrade) {
}
