package com.leyon.lumin.foundation.values;

/** 机审风险标记白名单（契约 MachineReport.riskFlags、05 §4.3）：最多 5 个、去重。 */
public enum RiskFlag {
    MINOR,
    ILLEGAL,
    MONEY_DISPUTE,
    PRIVACY,
    COPYRIGHT
}
