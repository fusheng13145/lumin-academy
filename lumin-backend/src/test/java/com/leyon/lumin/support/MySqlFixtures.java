package com.leyon.lumin.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 集成测试夹具（07 §1.1 固定夹具口径：固定业务时钟 + 服务主体 S；
 * 全部 UUID 固定且独立，仅合成数据）。提供最小外键链：user_profile → work → chapter。
 */
public final class MySqlFixtures {

    /** 07 §1.1 固定业务时钟：2026-11-12T00:00:00+08:00 → UTC。 */
    public static final LocalDateTime BUSINESS_TIME =
            LocalDateTime.ofInstant(java.time.Instant.parse("2026-11-11T16:00:00Z"), ZoneOffset.UTC);

    public static final String SYSTEM =
            UUID.fromString("0a0a0a0a-0000-4000-8000-000000000001").toString();
    public static final String AUTHOR_A =
            UUID.fromString("1b1b1b1b-0000-4000-8000-000000000002").toString();
    public static final String WORK_W =
            UUID.fromString("2c2c2c2c-0000-4000-8000-000000000003").toString();
    public static final String CHAPTER_C =
            UUID.fromString("3d3d3d3d-0000-4000-8000-000000000004").toString();
    public static final String CATEGORY_LITERATURE =
            UUID.fromString("4e4e4e4e-0000-4000-8000-000000000005").toString();
    public static final String READER_B =
            UUID.fromString("5f5f5f5f-0000-4000-8000-000000000006").toString();

    /** 骨架认证替身的固定主体（与 DemoAuthenticationFilter.STUB_SUBJECT 一致），测试据此建档。 */
    public static final String STUB_SUBJECT =
            UUID.fromString("9f8f8f8f-0000-4000-8000-0000000000a1").toString();
    /** 审核员替身主体（与 DemoAuthenticationFilter 替身集合一致）。 */
    public static final String REVIEWER_L1A =
            UUID.fromString("8f8f8f8f-0000-4000-8000-0000000000b2").toString();
    public static final String REVIEWER_L2A =
            UUID.fromString("7f7f7f7f-0000-4000-8000-0000000000c3").toString();
    /** 配置域替身主体（提案 OPERATOR / 审批 ADMIN；与过滤器集合一致）。 */
    public static final String OPERATOR =
            UUID.fromString("5e8f8f8f-0000-4000-8000-0000000000e1").toString();
    public static final String ADMIN =
            UUID.fromString("4d8f8f8f-0000-4000-8000-0000000000f2").toString();

    private MySqlFixtures() {
    }

    /** 已核验成年作者 A + 游客 B + 根分类 + 作品 W（ACTIVE）+ 章节 C（DRAFT）。
     *  固定 UUID，INSERT IGNORE 幂等（类内多测试共享同一套夹具行）。 */
    public static void insertAuthorWorkAndCategory(JdbcTemplate jdbc) {
        insertStubAuthorProfile(jdbc);
        jdbc.update("""
                INSERT IGNORE INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
                    declared_age_band, verified_age_band, effective_age_band, account_status,
                    policy_revision, created_at, updated_at, created_by, updated_by)
                VALUES (?, 'https://id.example', 'sub-A', '作者A', 'C3', 'C3', 'C3',
                    'ACTIVE', 1, ?, ?, ?, ?)
                """, AUTHOR_A, BUSINESS_TIME, BUSINESS_TIME, SYSTEM, SYSTEM);
        jdbc.update("""
                INSERT IGNORE INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
                    declared_age_band, effective_age_band, account_status,
                    policy_revision, created_at, updated_at, created_by, updated_by)
                VALUES (?, 'https://id.example', 'sub-B', '读者B', 'C2', 'C2',
                    'ACTIVE', 1, ?, ?, ?, ?)
                """, READER_B, BUSINESS_TIME, BUSINESS_TIME, SYSTEM, SYSTEM);
        jdbc.update("""
                INSERT IGNORE INTO category (id, parent_id, name, sort_order, enabled,
                    created_at, updated_at, created_by, updated_by)
                VALUES (?, NULL, '文学', 1, 1, ?, ?, ?, ?)
                """, CATEGORY_LITERATURE, BUSINESS_TIME, BUSINESS_TIME, SYSTEM, SYSTEM);
        jdbc.update("""
                INSERT IGNORE INTO work (id, author_id, business_state, copyright_status,
                    next_chapter_no, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'ACTIVE', 'VALID', 2, ?, ?, ?, ?)
                """, WORK_W, AUTHOR_A, BUSINESS_TIME, BUSINESS_TIME, SYSTEM, SYSTEM);
        jdbc.update("""
                INSERT IGNORE INTO chapter (id, work_id, sequence_no, business_state,
                    created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 1, 'DRAFT', ?, ?, ?, ?)
                """, CHAPTER_C, WORK_W, BUSINESS_TIME, BUSINESS_TIME, SYSTEM, SYSTEM);
    }

    /** 为骨架认证替身主体建档（已核验成年作者口径），供 /api/v1/works 外键使用。 */
    public static void insertStubAuthorProfile(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT IGNORE INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
                    declared_age_band, verified_age_band, effective_age_band, account_status,
                    policy_revision, created_at, updated_at, created_by, updated_by)
                VALUES (?, 'https://id.example', 'sub-stub-author', '骨架作者', 'C3', 'C3',
                    'C3', 'ACTIVE', 1, ?, ?, ?, ?)
                """, STUB_SUBJECT, BUSINESS_TIME, BUSINESS_TIME, SYSTEM, SYSTEM);
    }

    /** 配置域替身建档（提案 OPERATOR / 审批 ADMIN；幂等）。 */
    public static void insertConfigStubs(JdbcTemplate jdbc) {
        String[][] stubs = {
                {OPERATOR, "sub-operator", "运营"},
                {ADMIN, "sub-admin", "管理员"}};
        for (String[] stub : stubs) {
            jdbc.update("""
                    INSERT IGNORE INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
                        declared_age_band, verified_age_band, effective_age_band, account_status,
                        policy_revision, created_at, updated_at, created_by, updated_by)
                    VALUES (?, 'https://id.example', ?, ?, 'C3', 'C3', 'C3', 'ACTIVE',
                        1, ?, ?, ?, ?)
                    """, stub[0], stub[1], stub[2],
                    BUSINESS_TIME, BUSINESS_TIME, SYSTEM, SYSTEM);
        }
    }

    /** 读者替身 C0–C4 建档（T27 读者链矩阵；与 DemoAuthenticationFilter 集合一致，幂等）。 */
    public static void insertReaderProfiles(JdbcTemplate jdbc) {        String[] subjects = {
                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C0_SUBJECT,
                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C1_SUBJECT,
                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C2_SUBJECT,
                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3_SUBJECT,
                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C4_SUBJECT};
        String[] bands = {"C0", "C1", "C2", "C3", "C4"};
        for (int i = 0; i < bands.length; i++) {
            jdbc.update("""
                    INSERT IGNORE INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
                        declared_age_band, effective_age_band, account_status,
                        policy_revision, created_at, updated_at, created_by, updated_by)
                    VALUES (?, 'https://id.example', ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?, ?)
                    """, subjects[i], "sub-reader-" + bands[i].toLowerCase(),
                    "读者" + bands[i], bands[i], bands[i],
                    BUSINESS_TIME, BUSINESS_TIME, SYSTEM, SYSTEM);
        }
    }
}
