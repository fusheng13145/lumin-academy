package com.leyon.lumin.publication.domain;

import com.leyon.lumin.foundation.values.TargetType;

/**
 * 03 §2.1–§2.3「操作者与前提」列的全部布尔条件，由调用方从权威记录注入。
 *
 * <p>状态机只读取这些纯数据，不查询数据库、不读取当前系统时间、不做机审路由判定。
 */
public record TransitionContext(
        TargetType targetType,
        boolean authorIsOwner,
        boolean noPendingWorkflow,
        boolean allExistingChaptersPublished,
        String reason,
        boolean legalConfirmationsPresent,
        boolean priorStateRecorded,
        boolean allRestrictionsLifted,
        boolean copyrightValid,
        boolean accountAllows,
        boolean pointerRechecked,
        boolean machineReviewComplete,
        boolean noEffectiveDecision,
        boolean parentWorkHasVisibleApprovedVersion,
        boolean isGovernanceRestoration,
        boolean lockVersionMatches,
        boolean idempotentReplay) {
}
