package com.leyon.lumin.catalog.repository;

/** 章节业务态行（发布链业务态迁移读取；含乐观锁并发核验）。 */
public record ChapterStateRow(
        String id,
        String businessState) {
}
