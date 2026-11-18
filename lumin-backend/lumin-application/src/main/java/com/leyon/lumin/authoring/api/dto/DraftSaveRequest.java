package com.leyon.lumin.authoring.api.dto;

import java.util.List;

/**
 * 草稿保存载荷（05 §3.2 saveDraft）：按草稿自身 target 形态校验（服务层），
 * 不接受 targetType 变更——修改已提交内容须新建草稿（03 §2.2）。
 */
public record DraftSaveRequest(
        String title,
        String synopsis,
        String categoryId,
        List<String> tags,
        String body) {
}
