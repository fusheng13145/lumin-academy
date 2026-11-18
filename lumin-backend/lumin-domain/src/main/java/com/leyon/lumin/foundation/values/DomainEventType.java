package com.leyon.lumin.foundation.values;

/** 02 §5.2 九个事件名常量，仅作白名单对照；P0-A 不写事件发布代码。 */
public enum DomainEventType {
    IDENTITY_POLICY_CHANGED_V1,
    CONTENT_SUBMITTED_V1,
    REVIEW_RESOLVED_V1,
    CONTENT_PUBLISHED_V1,
    CONTENT_BLOCKED_V1,
    GOVERNANCE_CHANGED_V1,
    POLICY_ACTIVATED_V1,
    NOTIFICATION_CREATED_V1,
    AUDIT_RECORDED_V1
}
