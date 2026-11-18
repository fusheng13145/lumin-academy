-- =====================================================================
-- V6：法律保全与注销关闭（docs/04 §5.2 legal_hold 原文落地；03 §8 Tier0
-- 数据留存「注销立即断开公开关联；6 个月后匿名化内部关联；法律保全转 HELD
-- 并限制访问，不无限静默保留」；T36）。
-- account_closure 为注销推进状态行（04 未单列表格——以最小行登记注销时刻、
-- 内部匿名化到期时间与保全关联；审计事实不删）。
-- =====================================================================

-- 1. legal_hold（04 §5.2 governance）：所有保全读取留痕
CREATE TABLE legal_hold (
    id               CHAR(36)      NOT NULL,
    subject_type     VARCHAR(16)   NOT NULL,
    subject_id       CHAR(36)      NOT NULL,
    basis_ref_cipher VARBINARY(512) NOT NULL,
    state            VARCHAR(16)   NOT NULL,
    expires_at       DATETIME(6)   NULL,
    owner_id         CHAR(36)      NOT NULL,
    reason           VARCHAR(1000) NOT NULL,
    released_at      DATETIME(6)   NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    created_by       CHAR(36)      NOT NULL,
    updated_by       CHAR(36)      NOT NULL,
    lock_version     BIGINT        NOT NULL DEFAULT 1,
    CONSTRAINT pk_legal_hold PRIMARY KEY (id),
    CONSTRAINT uq_legal_hold_subject_state UNIQUE (subject_id, state),
    CONSTRAINT ck_legal_hold_subject CHECK (subject_type IN ('USER','WORK','CASE')),
    CONSTRAINT ck_legal_hold_state CHECK (state IN ('ACTIVE','RELEASED')),
    CONSTRAINT ck_legal_hold_basis CHECK (basis_ref_cipher IS NOT NULL AND LENGTH(basis_ref_cipher) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_legal_hold_subject ON legal_hold (subject_type, subject_id, state);

-- 2. account_closure（identity）：注销推进状态（PENDING/HELD → ANONYMIZED）
CREATE TABLE account_closure (
    id                CHAR(36)    NOT NULL,
    user_id           CHAR(36)    NOT NULL,
    state             VARCHAR(16) NOT NULL,
    requested_at      DATETIME(6) NOT NULL,
    anonymize_due_at  DATETIME(6) NOT NULL,
    hold_id           CHAR(36)    NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    created_by        CHAR(36)    NOT NULL,
    updated_by        CHAR(36)    NOT NULL,
    lock_version      BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT pk_account_closure PRIMARY KEY (id),
    CONSTRAINT uq_account_closure_user UNIQUE (user_id),
    CONSTRAINT fk_closure_user FOREIGN KEY (user_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_closure_state CHECK (state IN ('PENDING','HELD','ANONYMIZED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
