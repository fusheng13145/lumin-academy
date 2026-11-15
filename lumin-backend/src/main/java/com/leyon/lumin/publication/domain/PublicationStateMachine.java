package com.leyon.lumin.publication.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.BusinessState;
import com.leyon.lumin.foundation.values.DraftState;
import com.leyon.lumin.foundation.values.PublicationEvent;
import com.leyon.lumin.foundation.values.WorkflowState;

import java.util.Set;
import java.util.function.Predicate;

/**
 * 发布状态机（03 §2.1–§2.3 / §8 单元④、docs/10 A.3.1）。
 *
 * <p>只对 (state, event, ctx) 做表驱动纯映射：不查询数据库、不改状态、不产生
 * APPROVED 公开指针、不含机审阈值/分级/自动放行路由（N-02 / K-03）。
 * 未命中迁移表或前提不满足一律返回 B2001 + auditRequired=true；终态不可回退。
 * replay 只返回原结果，24h 幂等键去重属 P0-B 服务层（U-06）。
 */
public final class PublicationStateMachine {

    private PublicationStateMachine() {
    }

    public static TransitionResult business(BusinessState current, PublicationEvent event,
                                            TransitionContext ctx) {
        return evaluate(current, event, ctx);
    }

    public static TransitionResult draft(DraftState current, PublicationEvent event,
                                         TransitionContext ctx) {
        return evaluate(current, event, ctx);
    }

    public static TransitionResult workflow(WorkflowState current, PublicationEvent event,
                                            TransitionContext ctx) {
        return evaluate(current, event, ctx);
    }

    public static Set<PublicationEvent> allEvents() {
        return TransitionTable.allEvents();
    }

    /** 同事件重放由幂等层返回原结果，不重复执行迁移、不重复审计业务动作。 */
    public static TransitionResult replay(TransitionResult original) {
        return original;
    }

    private static TransitionResult evaluate(Enum<?> current, PublicationEvent event,
                                             TransitionContext ctx) {
        if (current == null || event == null || ctx == null) {
            return reject(current);
        }
        TransitionTable.TransitionRule rule = TransitionTable.lookup(current, event);
        if (rule == null) {
            return reject(current);
        }
        for (Predicate<TransitionContext> prerequisite : rule.prerequisites()) {
            if (!prerequisite.test(ctx)) {
                return reject(current);
            }
        }
        return new TransitionResult(true, ErrorCode.OK, current, rule.toState(),
                rule.versionState(), true, rule.terminal(), rule.ruleId());
    }

    private static TransitionResult reject(Enum<?> current) {
        return new TransitionResult(false, ErrorCode.B2001, current, current,
                null, true, false, "UNLISTED_TRANSITION");
    }
}
