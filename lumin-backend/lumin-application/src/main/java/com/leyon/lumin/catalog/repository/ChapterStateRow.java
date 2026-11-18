package com.leyon.lumin.catalog.repository;

/** 章节业务态行（发布/治理链业务态迁移读取；prior_state 供改判恢复）。 */
public record ChapterStateRow(
        String id,
        String businessState,
        String priorState) {
}
