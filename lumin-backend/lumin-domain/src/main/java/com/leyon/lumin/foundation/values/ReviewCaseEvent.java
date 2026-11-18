package com.leyon.lumin.foundation.values;

/** 审核案件状态机事件（03 §2.4 review_case 合法迁移；英文名 + 中文对照）。 */
public enum ReviewCaseEvent {
    /** 领取（含独立二审领取；15 分钟租约，租约核验属服务层） */
    CLAIM,
    /** 首审租约超时：CLAIMED→QUEUED */
    CLAIM_LEASE_EXPIRED,
    /** 首审完成（高风险首审后置阶段 2）：CLAIMED→WAITING_SECOND */
    FIRST_DECISION,
    /** 二审租约超时或意见分歧：CLAIMED→WAITING_SECOND，阶段置 3 */
    SECOND_LEASE_EXPIRED_OR_SPLIT,
    /** 升级仲裁：CLAIMED→WAITING_SECOND，阶段置 3，不记终局 */
    ESCALATE,
    /** 有效最终裁决：CLAIMED→DECIDED */
    DECIDE,
    /** 合法自动决定（机审路由 AUTO_*）：QUEUED→DECIDED */
    AUTO_DECIDE
}
