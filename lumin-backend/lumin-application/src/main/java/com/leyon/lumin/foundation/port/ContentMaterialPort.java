package com.leyon.lumin.foundation.port;

/**
 * 内容材料核验端口（02 §3.1：foundation 声明端口接口，authoring 域数据 +
 * catalog/identity 组装，publication 提供适配器）。事件消费方（N1 核验）与
 * 机审路由（authorAgeBand 权威源数据链）经此端口读取，不跨模块访问
 * authoring/catalog/identity Repository（02 §3.2）。
 */
public interface ContentMaterialPort {

    /** 版本材料摘要；版本不存在返回 null。 */
    MaterialRef findVersionMaterial(String versionId);

    /** MaterialRef.authorAgeBand 取权威源数据链 version→work→author→profile（03 §5.2）。 */
    record MaterialRef(String versionId, String workId, String chapterId,
                       String targetType, String materialSha256, Long policyVersion,
                       String authorAgeBand) {
    }
}
