package com.leyon.lumin.foundation.values;

/** 契约 Role 的 9 个人类角色（U-02：SYSTEM 不是角色，见 ServicePrincipals）。 */
public enum RoleCode {
    READER,
    AUTHOR,
    GUARDIAN,
    REVIEWER_L1,
    REVIEWER_L2,
    OPERATOR,
    ADMIN,
    AUDITOR,
    LEGAL;

    public boolean isReviewRole() {
        return this == REVIEWER_L1 || this == REVIEWER_L2;
    }

    /** 01 §3.3：AUTHOR / OPERATOR 不得兼任审核角色。 */
    public boolean isAccountMutexWithReview() {
        return this == AUTHOR || this == OPERATOR;
    }

    /** 宽容解析（未知字符串 → null；提案/绑定入参白名单校验用）。 */
    public static RoleCode valueOfOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return RoleCode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
