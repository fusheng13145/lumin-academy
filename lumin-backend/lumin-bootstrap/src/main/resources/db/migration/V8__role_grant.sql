-- =====================================================================
-- V8：角色授予链（docs/04 §5.2 role_grant 原文落地；docs/13 UGC-004）：
-- 提案（OPERATOR/ADMIN）→ 独立 ADMIN 审批（requested_by≠reviewed_by；
-- RolePolicy 互斥校验）→ APPROVED 即建 role_assignment 绑定（grant_id FK
-- 本迁移补建——V2 登记的待办）。到期/撤销不重复授予；审计事实不删。
-- =====================================================================

CREATE TABLE role_grant (
    id             CHAR(36)     NOT NULL,
    target_user_id CHAR(36)     NOT NULL,
    role           VARCHAR(32)  NOT NULL,
    scope_type     VARCHAR(16)  NOT NULL,
    scope_id       CHAR(36)     NULL,
    expires_at     DATETIME(6)  NULL,
    reason         VARCHAR(500) NOT NULL,
    state          VARCHAR(16)  NOT NULL,
    requested_by   CHAR(36)     NOT NULL,
    reviewed_by    CHAR(36)     NULL,
    reviewed_at    DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    created_by     CHAR(36)     NOT NULL,
    updated_by     CHAR(36)     NOT NULL,
    lock_version   BIGINT       NOT NULL DEFAULT 1,
    CONSTRAINT pk_role_grant PRIMARY KEY (id),
    CONSTRAINT fk_role_grant_user FOREIGN KEY (target_user_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_role_grant_role CHECK (role IN
        ('READER','AUTHOR','GUARDIAN','REVIEWER_L1','REVIEWER_L2','OPERATOR','ADMIN','AUDITOR','LEGAL')),
    CONSTRAINT ck_role_grant_state CHECK (state IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT ck_role_grant_scope CHECK (scope_type IN ('GLOBAL','WORK','CASE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_role_grant_state ON role_grant (state, created_at, id);
CREATE INDEX idx_role_grant_target ON role_grant (target_user_id, state);

-- V2 登记的待办：grant_id 外键补建
ALTER TABLE role_assignment
    ADD CONSTRAINT fk_role_assignment_grant FOREIGN KEY (grant_id)
        REFERENCES role_grant (id) ON DELETE RESTRICT;
