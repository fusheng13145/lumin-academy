package com.leyon.lumin.moderation.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.AppealEvent;
import com.leyon.lumin.foundation.values.AppealState;

/**
 * 申诉状态机（03 §2.4 appeal；T24/T25 状态面）。
 *
 * <p>合法迁移：OPEN→IN_REVIEW（受理）、IN_REVIEW→UPHELD（维持）、
 * IN_REVIEW→OVERTURNED（改判），两个终态；未列组合一律 B2001 + 审计。
 * 首次有效复核在服务层同一事务内完成 OPEN→IN_REVIEW→终态并记录两个迁移；
 * 原审全体回避、同案件唯一在途申诉由服务层核验（INV-05、04 §5）；
 * 改判不得直接把数据库改成 ACTIVE（03 §6），仅产出 OVERTURNED 结论。
 */
public final class AppealStateMachine {

    private static final String RULE_ILLEGAL_TRANSITION = "B2001_ILLEGAL_TRANSITION";

    private AppealStateMachine() {
    }

    /** 迁移结果：accepted=false 时 targetState 保持原状、code=B2001。 */
    public record TransitionResult(boolean accepted, AppealState targetState,
                                   ErrorCode code, boolean auditRequired, String ruleId) {
    }

    public static TransitionResult transit(AppealState current, AppealEvent event) {
        if (current == null || event == null) {
            return reject(current);
        }
        return switch (current) {
            case OPEN -> event == AppealEvent.START_REVIEW
                    ? accept(AppealState.IN_REVIEW, "APPEAL_REVIEW_STARTED")
                    : reject(current);
            case IN_REVIEW -> switch (event) {
                case UPHELD -> accept(AppealState.UPHELD, "APPEAL_UPHELD");
                case OVERTURNED -> accept(AppealState.OVERTURNED, "APPEAL_OVERTURNED");
                default -> reject(current);
            };
            case UPHELD, OVERTURNED -> reject(current);
        };
    }

    private static TransitionResult accept(AppealState target, String ruleId) {
        return new TransitionResult(true, target, ErrorCode.OK, true, ruleId);
    }

    private static TransitionResult reject(AppealState current) {
        return new TransitionResult(false, current, ErrorCode.B2001, true,
                RULE_ILLEGAL_TRANSITION);
    }
}
