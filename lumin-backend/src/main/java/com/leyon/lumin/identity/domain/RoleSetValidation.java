package com.leyon.lumin.identity.domain;

import java.util.Set;

/** 角色集互斥校验结果（01 §3.3：AUTHOR/OPERATOR 不得兼任审核角色）。 */
public record RoleSetValidation(
        boolean compatible,
        Set<String> conflictRuleIds) {
}
