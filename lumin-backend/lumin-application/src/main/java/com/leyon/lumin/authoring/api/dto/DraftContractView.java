package com.leyon.lumin.authoring.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 契约 DraftView（05 §3.2）：id/revision/state/content。
 * content 按 targetType 映射 WorkDraft 或 ChapterDraft；可空字段不输出。
 */
public record DraftContractView(String id, long revision, String state,
                                Map<String, Object> content) {

    public static DraftContractView of(String id, long revision, String state,
                                       String targetType, String workId, String chapterId,
                                       String baseVersionId, String title, String synopsis,
                                       String categoryId, List<String> tags,
                                       String coverAssetId, String body) {
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("targetType", targetType);
        content.put("workId", workId);
        if ("CHAPTER".equals(targetType)) {
            if (baseVersionId != null) {
                content.put("baseVersionId", baseVersionId);
            }
            content.put("chapterId", chapterId);
            content.put("title", title);
            content.put("body", body);
        } else {
            if (baseVersionId != null) {
                content.put("baseVersionId", baseVersionId);
            }
            content.put("title", title);
            content.put("synopsis", synopsis);
            content.put("categoryId", categoryId);
            content.put("tags", tags == null ? List.of() : tags);
            if (coverAssetId != null) {
                content.put("coverAssetId", coverAssetId);
            }
        }
        return new DraftContractView(id, revision, state, content);
    }
}
