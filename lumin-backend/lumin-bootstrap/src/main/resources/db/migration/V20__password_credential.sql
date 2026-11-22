-- =====================================================================
-- V20：口令凭据（2026-11-20 安全审查"必须先修"第 2/3 项落地；05 §3.1 账号）
--
-- 背景：P0 阶段签发端只有"有界替身"（X-Lumin-Demo-Subject），等于任何人可自报
-- 管理员。本表承载真实凭据：口令**仅存 BCrypt 摘要**（不存明文、不可逆），
-- 账号锁定状态落库（多实例一致，重启不丢）。
--
-- 设计约束：
--   1) 一主体一份口令凭据（uq_subject）；登录名全局唯一（uq_login）；
--   2) 失败计数与锁定时间在库内权威，服务层只做裁决（禁止内存态锁定）；
--   3) 口令策略见 identity.domain.PasswordPolicy（长度/字符类/弱口令/含登录名）；
--   4) 改密后由 AuthSessionService 撤销全部会话（旧令牌立即失效）。
-- =====================================================================

CREATE TABLE password_credential (
    id                   CHAR(36)    NOT NULL,
    subject              CHAR(36)    NOT NULL,
    login_id             VARCHAR(64) NOT NULL,
    password_hash        VARCHAR(100) NOT NULL,
    password_updated_at  DATETIME(6) NOT NULL,
    failed_count         INT         NOT NULL DEFAULT 0,
    locked_until         DATETIME(6) NULL,
    last_login_at        DATETIME(6) NULL,
    state                VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    created_by           CHAR(36)    NOT NULL,
    updated_by           CHAR(36)    NOT NULL,
    lock_version         BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT pk_password_credential PRIMARY KEY (id),
    CONSTRAINT uq_password_credential_login UNIQUE (login_id),
    CONSTRAINT uq_password_credential_subject UNIQUE (subject),
    CONSTRAINT ck_password_credential_state CHECK (state IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_password_credential_failed CHECK (failed_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_password_credential_lock ON password_credential (locked_until);
