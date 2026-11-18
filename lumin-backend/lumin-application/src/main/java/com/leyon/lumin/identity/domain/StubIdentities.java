package com.leyon.lumin.identity.domain;

import java.util.Map;
import java.util.UUID;

/**
 * 有界替身身份集合（05 §2 骨架口径：固定 UUID，测试/演示据此建档；
 * 不接受任意自报 ID。正式 OIDC/PKCE 落地后本集合整体移除——UGC-002 后续阶段）。
 */
public final class StubIdentities {

    public static final String AUTHOR = "AUTHOR";
    public static final String REVIEWER_L1A = "REVIEWER_L1A";
    public static final String REVIEWER_L2A = "REVIEWER_L2A";
    public static final String READER_C0 = "READER_C0";
    public static final String READER_C1 = "READER_C1";
    public static final String READER_C2 = "READER_C2";
    public static final String READER_C3 = "READER_C3";
    public static final String READER_C4 = "READER_C4";
    public static final String OPERATOR = "OPERATOR";
    public static final String ADMIN = "ADMIN";
    public static final String AUDITOR = "AUDITOR";

    public static final String STUB_SUBJECT =
            UUID.fromString("9f8f8f8f-0000-4000-8000-0000000000a1").toString();
    public static final String REVIEWER_L1A_SUBJECT =
            UUID.fromString("8f8f8f8f-0000-4000-8000-0000000000b2").toString();
    public static final String REVIEWER_L2A_SUBJECT =
            UUID.fromString("7f7f7f7f-0000-4000-8000-0000000000c3").toString();
    public static final String OPERATOR_SUBJECT =
            UUID.fromString("5e8f8f8f-0000-4000-8000-0000000000e1").toString();
    public static final String ADMIN_SUBJECT =
            UUID.fromString("4d8f8f8f-0000-4000-8000-0000000000f2").toString();
    public static final String AUDITOR_SUBJECT =
            UUID.fromString("3c8f8f8f-0000-4000-8000-0000000000a9").toString();
    public static final String READER_C0_SUBJECT =
            UUID.fromString("6f8f8f8f-0000-4000-8000-00000000d001").toString();
    public static final String READER_C1_SUBJECT =
            UUID.fromString("6f8f8f8f-0000-4000-8000-00000000d002").toString();
    public static final String READER_C2_SUBJECT =
            UUID.fromString("6f8f8f8f-0000-4000-8000-00000000d003").toString();
    public static final String READER_C3_SUBJECT =
            UUID.fromString("6f8f8f8f-0000-4000-8000-00000000d004").toString();
    public static final String READER_C4_SUBJECT =
            UUID.fromString("6f8f8f8f-0000-4000-8000-00000000d005").toString();

    /** 替身名称 → 主体 UUID（仅集合内成员可用）。 */
    public static final Map<String, String> SUBJECTS = Map.ofEntries(
            Map.entry(AUTHOR, STUB_SUBJECT),
            Map.entry(REVIEWER_L1A, REVIEWER_L1A_SUBJECT),
            Map.entry(REVIEWER_L2A, REVIEWER_L2A_SUBJECT),
            Map.entry(READER_C0, READER_C0_SUBJECT),
            Map.entry(READER_C1, READER_C1_SUBJECT),
            Map.entry(READER_C2, READER_C2_SUBJECT),
            Map.entry(READER_C3, READER_C3_SUBJECT),
            Map.entry(READER_C4, READER_C4_SUBJECT),
            Map.entry(OPERATOR, OPERATOR_SUBJECT),
            Map.entry(ADMIN, ADMIN_SUBJECT),
            Map.entry(AUDITOR, AUDITOR_SUBJECT));

    /** 替身名称 → 角色上下文（骨架授权口径；真实授权随 UGC-004 授予链路接线）。 */
    public static final Map<String, String> ROLES = Map.ofEntries(
            Map.entry(AUTHOR, "AUTHOR"),
            Map.entry(REVIEWER_L1A, "REVIEWER_L1"),
            Map.entry(REVIEWER_L2A, "REVIEWER_L2"),
            Map.entry(READER_C0, "READER"),
            Map.entry(READER_C1, "READER"),
            Map.entry(READER_C2, "READER"),
            Map.entry(READER_C3, "READER"),
            Map.entry(READER_C4, "READER"),
            Map.entry(OPERATOR, "OPERATOR"),
            Map.entry(ADMIN, "ADMIN"),
            Map.entry(AUDITOR, "AUDITOR"));

    private StubIdentities() {
    }
}
