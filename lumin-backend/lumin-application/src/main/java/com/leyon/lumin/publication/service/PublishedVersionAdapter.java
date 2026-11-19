package com.leyon.lumin.publication.service;

import com.leyon.lumin.foundation.port.PublishedVersionPort;
import com.leyon.lumin.publication.repository.PublicationPointerMapper;
import com.leyon.lumin.publication.repository.PublicationPointerRow;
import org.springframework.stereotype.Service;

/**
 * 当前公开版本查询适配器（publication 域）：仅供 foundation 端口消费方读取
 * publication_pointer，不暴露 publication Repository 行类型。
 */
@Service
public class PublishedVersionAdapter implements PublishedVersionPort {

    private final PublicationPointerMapper pointerMapper;

    public PublishedVersionAdapter(PublicationPointerMapper pointerMapper) {
        this.pointerMapper = pointerMapper;
    }

    @Override
    public String findPublishedVersionId(String targetType, String targetId) {
        if (targetType == null || targetId == null) {
            return null;
        }
        PublicationPointerRow pointer = pointerMapper.findByTarget(targetType, targetId);
        return pointer == null ? null : pointer.versionId();
    }
}
