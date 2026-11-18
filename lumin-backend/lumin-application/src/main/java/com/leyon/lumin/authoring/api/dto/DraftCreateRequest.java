package com.leyon.lumin.authoring.api.dto;

import java.util.List;

/**
 * 草稿创建载荷（05 §4.1）：WORK 载荷含 workId/title/synopsis/categoryId/tags，
 * chapterId 与 body 禁止；CHAPTER 载荷含 workId/chapterId/title/body，
 * synopsis/categoryId 禁止。字段是否允许出现由 DraftContentValidator 按 target 判定。
 */
public record DraftCreateRequest(
        String workId,
        String chapterId,
        String title,
        String synopsis,
        String categoryId,
        List<String> tags,
        String body) {
}
