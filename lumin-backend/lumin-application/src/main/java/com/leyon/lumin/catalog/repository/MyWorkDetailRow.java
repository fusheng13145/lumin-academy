package com.leyon.lumin.catalog.repository;

/** 作者私有作品详情行（05 §3.2 getMyWork；草稿与公开指针均为可空）。 */
public record MyWorkDetailRow(
        String id,
        String businessState,
        String title,
        String draftId,
        String draftState,
        String publishedVersionId,
        Long lockVersion) {
}
