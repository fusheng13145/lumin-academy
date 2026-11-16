package com.leyon.lumin.authoring.api.dto;

import java.util.List;

/**
 * 草稿视图（05 §3.2 getDraft：返回草稿与 ETag；revision 即乐观锁版本）。
 * 正文仅对本人可见（越权统一 404，05 §2）。
 */
public record DraftView(
        String id,
        String workId,
        String chapterId,
        String targetType,
        String title,
        String synopsis,
        String categoryId,
        List<String> tags,
        String coverAssetId,
        String bodyText,
        String state,
        long revision) {
}
