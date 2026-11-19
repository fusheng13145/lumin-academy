package com.leyon.lumin.publication.service;

import com.leyon.lumin.authoring.service.DraftLifecycleService;
import com.leyon.lumin.foundation.port.ContentMaterialPort;
import org.springframework.stereotype.Service;

/**
 * 内容材料核验适配器（publication 域：允许边 publication→authoring/catalog/identity
 * 全覆盖所需组装；moderation 等事件消费方经 foundation 端口消费本适配器）。
 */
@Service
public class ContentMaterialAdapter implements ContentMaterialPort {

    private final DraftLifecycleService draftLifecycleService;

    public ContentMaterialAdapter(DraftLifecycleService draftLifecycleService) {
        this.draftLifecycleService = draftLifecycleService;
    }

    @Override
    public ContentMaterialPort.MaterialRef findVersionMaterial(String versionId) {
        return draftLifecycleService.findVersionMaterial(versionId);
    }

    @Override
    public java.util.List<ContentMaterialPort.MaterialRef> findVersionMaterials(
            java.util.List<String> versionIds) {
        return draftLifecycleService.findVersionMaterials(versionIds);
    }

    @Override
    public ContentMaterialPort.ReviewMaterial findReviewMaterial(String versionId) {
        return draftLifecycleService.findReviewMaterial(versionId);
    }
}
