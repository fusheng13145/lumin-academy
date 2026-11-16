-- =====================================================================
-- V1.1：role_assignment（docs/04 §3 identity；人审领取/裁决的角色权威来源）
-- 登记简化：grant_id 外键（role_grant 表）待 V2 迁移落地后补建；
--   授予链路（role_grant 提案-审批）随治理批次落地。
-- =====================================================================
CREATE TABLE role_assignment (
    id          CHAR(36)    NOT NULL,
    user_id     CHAR(36)    NOT NULL,
    role        VARCHAR(32) NOT NULL,
    scope_type  VARCHAR(16) NOT NULL,
    scope_id    CHAR(36)    NULL,
    expires_at  DATETIME(6) NULL,
    grant_id    CHAR(36)    NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    created_by  CHAR(36)    NOT NULL,
    updated_by  CHAR(36)    NOT NULL,
    lock_version BIGINT     NOT NULL DEFAULT 1,
    deleted_at  DATETIME(6) NULL,
    -- 未软删绑定唯一（04 §3 NULLS NOT DISTINCT → scope_slot/deleted_slot 生成列模拟；
    -- scope_id 归一：GLOBAL 无 scope_id，统一以 'GLOBAL' 字面量参与唯一约束）
    scope_slot  CHAR(36) GENERATED ALWAYS AS (COALESCE(scope_id, 'GLOBAL')) STORED,
    active_slot TINYINT GENERATED ALWAYS AS
        (CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END) STORED,
    CONSTRAINT pk_role_assignment PRIMARY KEY (id),
    CONSTRAINT uq_role_assignment_active UNIQUE (user_id, role, scope_type, scope_slot, active_slot),
    CONSTRAINT fk_role_assignment_user FOREIGN KEY (user_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_role_assignment_role CHECK (role IN
        ('READER','AUTHOR','GUARDIAN','REVIEWER_L1','REVIEWER_L2','OPERATOR','ADMIN','AUDITOR','LEGAL')),
    CONSTRAINT ck_role_assignment_scope CHECK (scope_type IN ('GLOBAL','WORK','QUEUE','CATEGORY')),
    -- GLOBAL 的 scope_id 必须为空（04 §3）
    CONSTRAINT ck_role_assignment_global CHECK (
        (scope_type = 'GLOBAL' AND scope_id IS NULL)
        OR (scope_type <> 'GLOBAL' AND scope_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_role_assignment_user ON role_assignment (user_id, role, deleted_at);
