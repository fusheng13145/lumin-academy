package com.leyon.lumin.configuration.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.ConfigRevisionEvent;
import com.leyon.lumin.foundation.values.ConfigRevisionState;

/**
 * 配置修订状态机（03 §2.4 config_revision；T30–T32 状态面）。
 *
 * <p>合法迁移：DRAFT→IN_REVIEW、IN_REVIEW→APPROVED、IN_REVIEW→REJECTED、
 * APPROVED→CANCELLED、APPROVED→ACTIVE、ACTIVE→SUPERSEDED；未列组合一律
 * B2001 + 审计。配置初建直接 IN_REVIEW（03 §2.4：初建即走 SUBMIT_FOR_REVIEW）。
 *
 * <p>回滚创建新修订重复审批、不修改历史；只有 ACTIVE 参与决策；审批人独立性、
 * base_revision 绑定、并发激活锁 config_key、槽位替换旧版本置 SUPERSEDED
 * 由服务层在同一事务内核验（03 §2.4 / 05 §4.2），本机只做状态映射。
 */
public final class ConfigRevisionStateMachine {

    private static final String RULE_ILLEGAL_TRANSITION = "B2001_ILLEGAL_TRANSITION";

    private ConfigRevisionStateMachine() {
    }

    /** 迁移结果：accepted=false 时 targetState 保持原状、code=B2001。 */
    public record TransitionResult(boolean accepted, ConfigRevisionState targetState,
                                   ErrorCode code, boolean auditRequired, String ruleId) {
    }

    public static TransitionResult transit(ConfigRevisionState current,
                                           ConfigRevisionEvent event) {
        if (current == null || event == null) {
            return reject(current);
        }
        return switch (current) {
            case DRAFT -> event == ConfigRevisionEvent.SUBMIT_FOR_REVIEW
                    ? accept(ConfigRevisionState.IN_REVIEW, "CONFIG_SUBMITTED_FOR_REVIEW")
                    : reject(current);
            case IN_REVIEW -> switch (event) {
                case APPROVE -> accept(ConfigRevisionState.APPROVED, "CONFIG_APPROVED");
                case REJECT -> accept(ConfigRevisionState.REJECTED, "CONFIG_REJECTED");
                default -> reject(current);
            };
            case APPROVED -> switch (event) {
                case ACTIVATE -> accept(ConfigRevisionState.ACTIVE, "CONFIG_ACTIVATED");
                case CANCEL -> accept(ConfigRevisionState.CANCELLED, "CONFIG_CANCELLED");
                default -> reject(current);
            };
            case ACTIVE -> event == ConfigRevisionEvent.SUPERSEDE
                    ? accept(ConfigRevisionState.SUPERSEDED, "CONFIG_SUPERSEDED")
                    : reject(current);
            case REJECTED, CANCELLED, SUPERSEDED -> reject(current);
        };
    }

    private static TransitionResult accept(ConfigRevisionState target, String ruleId) {
        return new TransitionResult(true, target, ErrorCode.OK, true, ruleId);
    }

    private static TransitionResult reject(ConfigRevisionState current) {
        return new TransitionResult(false, current, ErrorCode.B2001, true,
                RULE_ILLEGAL_TRANSITION);
    }
}
