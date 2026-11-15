package com.leyon.lumin.identity.domain;

import com.leyon.lumin.foundation.values.RoleCode;
import com.leyon.lumin.foundation.values.ScopeType;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 授权上下文（docs/10 A.3.3）：全部为调用方从权威记录注入的纯数据，
 * 策略本身不查询数据库、不读取当前系统时间、不改状态。
 */
public record AuthorizationContext(
        UUID subjectId,
        Set<RoleCode> activeRoles,
        Action action,
        UUID resourceOwnerId,
        ScopeType scopeType,
        UUID scopeId,
        UUID applicantId,
        UUID approverId,
        Set<UUID> originalDecisionActorIds,
        int reviewStage,
        boolean highRisk,
        UUID secondSeatActorId,
        Instant roleExpiresAt,
        Instant now) {
}
