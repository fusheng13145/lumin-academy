package com.leyon.lumin.publication.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.VersionState;

/** 状态机迁移结果；replay 只返回原结果，不重复执行迁移（U-06）。 */
public record TransitionResult(
        boolean accepted,
        ErrorCode code,
        Enum<?> fromState,
        Enum<?> toState,
        VersionState versionState,
        boolean auditRequired,
        boolean terminal,
        String ruleId) {

    public boolean isReject() {
        return !accepted;
    }
}
