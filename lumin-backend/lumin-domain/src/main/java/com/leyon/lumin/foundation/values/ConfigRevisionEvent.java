package com.leyon.lumin.foundation.values;

/** 配置修订状态机事件（03 §2.4 config_revision 合法迁移；审批人独立性由服务层核验）。 */
public enum ConfigRevisionEvent {
    /** 提交评审：DRAFT→IN_REVIEW（配置初建直接 IN_REVIEW，即创建后立即走本迁移） */
    SUBMIT_FOR_REVIEW,
    /** 审批通过：IN_REVIEW→APPROVED（绑定 base_revision，批后不得替换 payload） */
    APPROVE,
    /** 审批拒绝：IN_REVIEW→REJECTED */
    REJECT,
    /** 批准后撤回：APPROVED→CANCELLED */
    CANCEL,
    /** 到生效时间激活：APPROVED→ACTIVE（并发激活锁 config_key，替换槽位旧版本置 SUPERSEDED） */
    ACTIVATE,
    /** 被新版本取代：ACTIVE→SUPERSEDED */
    SUPERSEDE
}
