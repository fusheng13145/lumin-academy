package com.leyon.lumin.publication.service;

import com.leyon.lumin.foundation.port.ChapterAccessPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 章节可读性端口适配器（engagement 阅读进度的可见裁决来源）：
 * 裁决逻辑单源在 {@link ReaderService}（VisibilityPolicy 八步），
 * 本适配器仅做跨域桥接（publication→engagement 依赖方向禁止，
 * 故由 engagement 经 foundation 端口反向调用）。
 */
@Service
public class ChapterAccessAdapter implements ChapterAccessPort {

    private final ReaderService readerService;

    public ChapterAccessAdapter(ReaderService readerService) {
        this.readerService = readerService;
    }

    @Override
    @Transactional(readOnly = true)
    public String readableVersion(String subject, String chapterId) {
        return readerService.readableVersion(subject, chapterId);
    }
}
