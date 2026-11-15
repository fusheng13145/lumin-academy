package com.leyon.lumin.identity.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.AgeBand;

/** 年龄策略的不可变输出（01 §3.2、03 §8 单元①）。 */
public record AgeAssessment(
        AgeBand effectiveAgeBand,
        boolean canPublish,
        boolean requiresGuardianConsent,
        boolean requiresHumanReview,
        boolean blockedByGuardianRevocation,
        boolean requiresReverify,
        ErrorCode denialCode) {
}
