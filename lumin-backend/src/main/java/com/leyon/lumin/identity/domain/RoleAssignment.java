package com.leyon.lumin.identity.domain;

import com.leyon.lumin.foundation.values.RoleCode;
import com.leyon.lumin.foundation.values.ScopeType;

import java.time.Instant;
import java.util.UUID;

/** 角色绑定（04 §3 role_assignment）；时间由调用方注入，不读取当前系统时间。 */
public record RoleAssignment(
        UUID userId,
        RoleCode role,
        ScopeType scopeType,
        UUID scopeId,
        Instant expiresAt) {

    public boolean isActiveAt(Instant now) {
        return now != null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
