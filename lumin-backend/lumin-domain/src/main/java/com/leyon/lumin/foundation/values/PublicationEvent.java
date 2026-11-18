package com.leyon.lumin.foundation.values;

/**
 * 状态机事件（03 §2.1–§2.3 中文原文的工程标识，不新增事件语义）。
 * UNDEFINED 仅用于非法迁移注入（07 §2.2 T15）。
 */
public enum PublicationEvent {
    FIRST_VERSION_PUBLISHED,
    COMPLETE_REQUESTED,
    RESUME_SERIAL,
    TAKEDOWN_EFFECTIVE,
    TEMPORARY_APPEAL_UPHELD,
    CONTRACT_TERMINATED,
    SAVE,
    SUBMIT,
    DISCARD,
    AUTO_TASK_CLAIMED,
    AUTO_PASS,
    AUTO_REVISE,
    ROUTE_HUMAN_REVIEW,
    SUSPEND,
    RETRY_TO_HUMAN,
    HUMAN_FINAL_PASS,
    HUMAN_FINAL_REJECT,
    WITHDRAW,
    UNDEFINED
}
