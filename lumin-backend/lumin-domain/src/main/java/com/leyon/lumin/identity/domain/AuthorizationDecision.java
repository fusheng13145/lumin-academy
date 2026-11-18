package com.leyon.lumin.identity.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;

/** 授权结果；默认拒绝时 ruleId 固定为 DEFAULT_DENY（01 §3.3、INV-05）。 */
public record AuthorizationDecision(
        boolean allowed,
        ErrorCode denialCode,
        String ruleId) {
}
