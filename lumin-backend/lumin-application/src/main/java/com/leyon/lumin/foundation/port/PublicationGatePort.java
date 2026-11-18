package com.leyon.lumin.foundation.port;

/**
 * 生效治理措施的业务态闸门端口（03 §2.1 生效下架 / 改判恢复）：
 * governance 域在限制生效/解除时经本端口驱动 publication/catalog 的
 * 业务状态机——governance 不直接触碰业务行（INV-03 归属边界）。
 * 实现方为 publication 域（publication→catalog 允许边）。
 */
public interface PublicationGatePort {

    /** 生效下架：目标业务态 →(TAKEDOWN_EFFECTIVE)→ REMOVED，保存前态。 */
    void takedown(String targetType, String workId, String chapterId);

    /**
     * 改判恢复：仅当所有生效限制均已解除且发布指针仍在（重新校验）时，
     * REMOVED →(TEMPORARY_APPEAL_UPHELD)→ 记录的前态；前提不满足时静默不动。
     */
    void restore(String targetType, String workId, String chapterId);
}
