package com.leyon.lumin.moderation.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.ReviewCaseEvent;
import com.leyon.lumin.foundation.values.ReviewCaseState;

/**
 * 审核案件状态机（03 §2.4 review_case；T20 / T21 的状态面）。
 *
 * <p>表驱动纯映射：{@code (state, event, stage) → 目标}；未列组合一律
 * {@code B2001} + {@code auditRequired=true}。阶段语义：1=首审、2=二审
 * （同结果且同 grade 为双审一致）、3=仲裁（仅未参与原裁决的新 L2 领取）。
 *
 * <p>租约 15 分钟、回避集合、双审一致性、HIGH 双人等前提由服务层在同一事务内
 * 核验后调用本机（03 §2.4 末段），本机不做授权判定；ESCALATE 仅允许自
 * CLAIMED（处理中升级），QUEUED 案件由路由直接自动决定或领取后处理。
 */
public final class ReviewCaseStateMachine {

    public static final int MIN_STAGE = 1;
    public static final int MAX_STAGE = 3;
    /** 02 §3.1 A-01 通用错误码：非法迁移。 */
    private static final String RULE_ILLEGAL_TRANSITION = "B2001_ILLEGAL_TRANSITION";

    private ReviewCaseStateMachine() {
    }

    /** 案件迁移结果：accepted=false 时 targetState/targetStage 保持原值、code=B2001。 */
    public record TransitionResult(boolean accepted, ReviewCaseState targetState,
                                   int targetStage, ErrorCode code, boolean auditRequired,
                                   String ruleId) {
    }

    public static TransitionResult transit(ReviewCaseState current, ReviewCaseEvent event,
                                           int currentStage) {
        if (current == null || event == null
                || currentStage < MIN_STAGE || currentStage > MAX_STAGE) {
            return reject(current, currentStage);
        }
        return switch (current) {
            case QUEUED -> switch (event) {
                case CLAIM -> accept(ReviewCaseState.CLAIMED, currentStage,
                        "CLAIM_ASSIGNED");
                case AUTO_DECIDE -> accept(ReviewCaseState.DECIDED, currentStage,
                        "AUTO_DECIDED");
                default -> reject(current, currentStage);
            };
            case CLAIMED -> switch (event) {
                case CLAIM_LEASE_EXPIRED -> accept(ReviewCaseState.QUEUED, currentStage,
                        "LEASE_EXPIRED_BACK_TO_QUEUE");
                case FIRST_DECISION -> currentStage == 1
                        ? accept(ReviewCaseState.WAITING_SECOND, 2, "FIRST_DECISION_RECORDED")
                        : reject(current, currentStage);
                case SECOND_LEASE_EXPIRED_OR_SPLIT -> {
                    // 03 §2.4：阶段 2 租约超时或分歧 → 阶段 3；阶段 3 租约超时回
                    // WAITING_SECOND 且阶段保持 3；阶段 1 无二审语义，拒绝
                    yield currentStage == 2
                            ? accept(ReviewCaseState.WAITING_SECOND, 3, "SPLIT_TO_ARBITRATION")
                            : currentStage == 3
                            ? accept(ReviewCaseState.WAITING_SECOND, 3, "STAGE3_LEASE_EXPIRED")
                            : reject(current, currentStage);
                }
                case ESCALATE -> currentStage < 3
                        ? accept(ReviewCaseState.WAITING_SECOND, 3, "ESCALATED_TO_STAGE3")
                        : reject(current, currentStage);
                case DECIDE -> accept(ReviewCaseState.DECIDED, currentStage,
                        "FINAL_DECISION_RECORDED");
                default -> reject(current, currentStage);
            };
            case WAITING_SECOND -> switch (event) {
                case CLAIM -> accept(ReviewCaseState.CLAIMED, currentStage,
                        "SECOND_CLAIM_ASSIGNED");
                default -> reject(current, currentStage);
            };
            case DECIDED -> reject(current, currentStage);
        };
    }

    private static TransitionResult accept(ReviewCaseState target, int targetStage, String ruleId) {
        return new TransitionResult(true, target, targetStage, ErrorCode.OK, true, ruleId);
    }

    private static TransitionResult reject(ReviewCaseState current, int currentStage) {
        return new TransitionResult(false, current, currentStage,
                ErrorCode.B2001, true, RULE_ILLEGAL_TRANSITION);
    }
}
