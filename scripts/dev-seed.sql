-- 本地开发演示种子数据（仅限开发库 lumin；生产严禁执行）
-- Flyway 重建 schema 后运行：mysql -uroot -p lumin < scripts/dev-seed.sql
USE lumin;

INSERT IGNORE INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
    declared_age_band, verified_age_band, effective_age_band, account_status,
    policy_revision, created_at, updated_at, created_by, updated_by) VALUES
    ('9f8f8f8f-0000-4000-8000-0000000000a1', 'https://id.example', 'sub-stub-author', '骨架作者', 'C3', 'C3', 'C3', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'system', 'system'),
    ('8f8f8f8f-0000-4000-8000-0000000000b2', 'https://id.example', 'sub-reviewer-l1a', '审核员', 'C3', 'C3', 'C3', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'system', 'system'),
    ('7f7f7f7f-0000-4000-8000-0000000000c3', 'https://id.example', 'sub-reviewer-l2a', '审核员二', 'C3', 'C3', 'C3', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'system', 'system'),
    ('5e8f8f8f-0000-4000-8000-0000000000e1', 'https://id.example', 'sub-operator', '运营', 'C3', 'C3', 'C3', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'system', 'system'),
    ('4d8f8f8f-0000-4000-8000-0000000000f2', 'https://id.example', 'sub-admin', '管理员', 'C3', 'C3', 'C3', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'system', 'system'),
    ('3c8f8f8f-0000-4000-8000-0000000000a9', 'https://id.example', 'sub-auditor', '审计员', 'C3', 'C3', 'C3', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'system', 'system'),
    ('6f8f8f8f-0000-4000-8000-00000000d001', 'https://id.example', 'sub-reader-c0', '读者C0', 'C0', 'C0', 'C0', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'system', 'system'),
    ('6f8f8f8f-0000-4000-8000-00000000d002', 'https://id.example', 'sub-reader-c1', '读者C1', 'C1', 'C1', 'C1', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'system', 'system'),
    ('6f8f8f8f-0000-4000-8000-00000000d003', 'https://id.example', 'sub-reader-c2', '读者C2', 'C2', 'C2', 'C2', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'system', 'system'),
    ('6f8f8f8f-0000-4000-8000-00000000d004', 'https://id.example', 'sub-reader-c3', '读者C3', 'C3', 'C3', 'C3', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'system', 'system'),
    ('6f8f8f8f-0000-4000-8000-00000000d005', 'https://id.example', 'sub-reader-c4', '读者C4', 'C4', 'C4', 'C4', 'ACTIVE', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'system', 'system');

INSERT IGNORE INTO category (id, parent_id, name, sort_order, enabled,
    created_at, updated_at, created_by, updated_by) VALUES
    ('a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', NULL, '文学', 1, 1,
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'system', 'system');
