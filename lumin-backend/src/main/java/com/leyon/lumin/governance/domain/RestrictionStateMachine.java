package com.leyon.lumin.governance.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.RestrictionEvent;
import com.leyon.lumin.foundation.values.RestrictionKind;
import com.leyon.lumin.foundation.values.RestrictionState;

/**
 * 治理措施状态机（03 §2.4 restriction；T24–T26 状态面）。
 *
 * <p>合法迁移：PENDING→ACTIVE（临时、终局及解约提案均生效，审批资格属服务层）、
 * PENDING→REJECTED、ACTIVE→REVOKED（仅临时改判：TEMPORARY / GUARDIAN_REVOKED；
 * FINAL_VIOLATION 与 CONTRACT_TERMINATED 禁止 REVOKED——不可恢复传播，B2005
 * 服务层语义对应此处 B2001 拒绝）。未列组合一律 B2001 + 审计。
 *
 * <p>生效限制优先于任何发布指针（INV-03）；expiresAt 到期进入复核告警、
 * 不自动公开（03 §2.4），定时扫描属服务层。
 */
public final class RestrictionStateMachine {

    private static final String RULE_ILLEGAL_TRANSITION = "B2001_ILLEGAL_TRANSITION";

    private RestrictionStateMachine() {
    }

    /** 迁移结果：accepted=false 时 targetState 保持原状、code=B2001。 */
    public record TransitionResult(boolean accepted, RestrictionState targetState,
                                   ErrorCode code, boolean auditRequired, String ruleId) {
    }

    public static TransitionResult transit(RestrictionState current, RestrictionEvent event,
                                           RestrictionKind kind) {
        if (current == null || event == null || kind == null) {
            return reject(current);
        }
        return switch (current) {
            case PENDING -> switch (event) {
                case ACTIVATE -> accept(RestrictionState.ACTIVE, "RESTRICTION_ACTIVATED");
                case REJECT -> accept(RestrictionState.REJECTED, "RESTRICTION_REJECTED");
                default -> reject(current);
            };
            case ACTIVE -> event == RestrictionEvent.REVOKE && revocable(kind)
                    ? accept(RestrictionState.REVOKED, "RESTRICTION_REVOKED")
                    : reject(current);
            case REVOKED, REJECTED -> reject(current);
        };
    }

    /** 仅临时措施与监护撤销措施可改判解除；终局违规与合同终止不可恢复传播。 */
    private static boolean revocable(RestrictionKind kind) {
        return kind == RestrictionKind.TEMPORARY || kind == RestrictionKind.GUARDIAN_REVOKED;
    }

    private static TransitionResult accept(RestrictionState target, String ruleId) {
        return new TransitionResult(true, target, ErrorCode.OK, true, ruleId);
    }

    private static TransitionResult reject(RestrictionState current) {
        return new TransitionResult(false, current, ErrorCode.B2001, true,
                RULE_ILLEGAL_TRANSITION);
    }
}
