package com.leyon.lumin.foundation.values;

/** 治理措施状态（03 §2.4 restriction / 04 §5 restriction.state）。 */
public enum RestrictionState {
    PENDING,
    ACTIVE,
    REVOKED,
    REJECTED,
    /** 临时措施到期：退出生效集合、进入复核告警；不自动恢复公开。 */
    EXPIRED
}
