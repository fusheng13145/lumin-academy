package com.leyon.lumin.moderation.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.Grade;
import com.leyon.lumin.foundation.values.ReviewDecision;

/**
 * grade 配对规则（05 §4.3 / 04 §5.2 末段）：机审与人工裁决共用。
 *
 * <p>PASS / LABEL / LIMIT 必须携带 grade；REVISE / HIDE / REMOVE / BAN / ESCALATE
 * 禁止携带 grade（含显式 null）。违反一律 400/P1001（T18：PASS 缺 grade 及
 * REVISE 带 grade 均拒绝）。
 */
public final class ReviewDecisionRule {

    private ReviewDecisionRule() {
    }

    /** 返回 {@link ErrorCode#OK} 表示合法，否则为拒绝错误码。 */
    public static ErrorCode check(ReviewDecision decision, Grade grade) {
        if (decision == null) {
            return ErrorCode.P1001;
        }
        if (decision.requiresGrade()) {
            return grade == null ? ErrorCode.P1001 : ErrorCode.OK;
        }
        return grade == null ? ErrorCode.OK : ErrorCode.P1001;
    }
}
