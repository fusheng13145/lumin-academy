-- =====================================================================
-- V7：会话令牌认证（docs/13 UGC-002 一阶段；05 §2）：
-- 服务端权威会话——原始令牌不落库（仅存 SHA-256 摘要），撤销/过期/伪造
-- 一律 401/A0001；`lumin.auth.mode=session` 时演示身份头被忽略（生产
-- 无法使用演示头的结构性闸门）。OIDC/PKCE 随 UGC-002 后续阶段替换签发端。
-- =====================================================================

CREATE TABLE auth_session (
    id            CHAR(36)    NOT NULL,
    token_hash    CHAR(64)    NOT NULL,
    subject       CHAR(36)    NOT NULL,
    stub_name     VARCHAR(24) NOT NULL,
    role_context  VARCHAR(16) NOT NULL,
    issued_at     DATETIME(6) NOT NULL,
    expires_at    DATETIME(6) NOT NULL,
    revoked_at    DATETIME(6) NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    created_by    CHAR(36)    NOT NULL,
    updated_by    CHAR(36)    NOT NULL,
    lock_version  BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT pk_auth_session PRIMARY KEY (id),
    CONSTRAINT uq_auth_session_token UNIQUE (token_hash),
    CONSTRAINT ck_auth_session_revoked CHECK (revoked_at IS NULL OR revoked_at <= updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_auth_session_subject ON auth_session (subject, revoked_at);
