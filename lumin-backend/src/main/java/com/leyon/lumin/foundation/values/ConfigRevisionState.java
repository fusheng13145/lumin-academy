package com.leyon.lumin.foundation.values;

/** 配置修订状态（03 §2.4 config_revision）：只有 ACTIVE 参与决策；回滚创建新修订，不改历史。 */
public enum ConfigRevisionState {
    DRAFT,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    CANCELLED,
    ACTIVE,
    SUPERSEDED
}
