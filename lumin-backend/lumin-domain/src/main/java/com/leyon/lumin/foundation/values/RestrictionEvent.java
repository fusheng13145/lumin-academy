package com.leyon.lumin.foundation.values;

/** 治理措施状态机事件（03 §2.4 restriction 合法迁移；英文名 + 中文对照）。 */
public enum RestrictionEvent {
    /** 提案生效：PENDING→ACTIVE（临时、终局及解约提案均走此迁移；审批人资格属服务层） */
    ACTIVATE,
    /** 临时措施独立复核改判解除：ACTIVE→REVOKED（终局违规/合同终止禁止） */
    REVOKE,
    /** 申请驳回：PENDING→REJECTED */
    REJECT
}
