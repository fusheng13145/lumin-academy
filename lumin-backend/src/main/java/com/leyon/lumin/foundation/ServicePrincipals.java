package com.leyon.lumin.foundation;

import java.util.UUID;

/**
 * 固定不可登录服务主体（04 §1.1、01 §3.3 第 10 行）。
 *
 * <p>U-02 裁决：SYSTEM <strong>不是</strong>契约 Role 枚举的 9 个人类角色之一，
 * 不纳入 RoleCode，只以固定 UUID 表达，且不是可授予角色。
 */
public final class ServicePrincipals {

    /** 与 FixedFixtures.S 同一来源：合成固定 UUID 第 14 号。 */
    public static final UUID SYSTEM_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000014");

    private ServicePrincipals() {
    }

    public static boolean isSystem(UUID subjectId) {
        return SYSTEM_ID.equals(subjectId);
    }
}
