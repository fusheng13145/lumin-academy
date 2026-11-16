package com.leyon.lumin.foundation.values;

/** 申诉状态机事件（03 §2.4 appeal 合法迁移；原审全体回避由服务层核验）。 */
public enum AppealEvent {
    /** 受理复核：OPEN→IN_REVIEW */
    START_REVIEW,
    /** 维持原裁决：IN_REVIEW→UPHELD（终态） */
    UPHELD,
    /** 改判/推翻临时措施：IN_REVIEW→OVERTURNED（终态；不直接改库为 ACTIVE） */
    OVERTURNED
}
