package com.leyon.lumin.foundation.port;

/**
 * 审查案件是否已有生效裁决（publication 撤回流程的 noEffectiveDecision 前提）。
 * 实现方为 moderation；publication 只依赖本基础端口，不跨模块直读 review_case。
 */
public interface ReviewCaseDecisionPort {

    /** 案件不存在或未裁决返回 false。 */
    boolean hasEffectiveDecision(String caseId);
}
