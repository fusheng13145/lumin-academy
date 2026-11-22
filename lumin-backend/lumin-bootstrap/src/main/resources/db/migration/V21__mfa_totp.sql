-- =====================================================================
-- V21：二次验证（TOTP，2026-11-20 安全审查 05 项"管理员额外要求"落地）
--
-- 设计约束：
--   1) TOTP 种子属于**高敏凭证**：库内只存 FieldCipher(AES-256-GCM) 密文，
--      AAD 绑定 'mfa.totp.secret'（防跨字段密文互换），绝不存明文；
--   2) 重放保护：last_used_step 记录最近一次通过校验的时间步，
--      同一时间步的验证码只能用一次（RFC 6238 §5.2 建议）；
--   3) 恢复码只存 SHA-256 摘要、单次使用（used_at 置位后不可再用）；
--   4) 状态机 PENDING（已生成未确认）→ ACTIVE（已确认生效）。
-- =====================================================================

CREATE TABLE mfa_totp_credential (
    id             CHAR(36)     NOT NULL,
    subject        CHAR(36)     NOT NULL,
    secret_cipher  VARBINARY(255) NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    last_used_step BIGINT       NULL,
    activated_at   DATETIME(6)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    created_by     CHAR(36)     NOT NULL,
    updated_by     CHAR(36)     NOT NULL,
    lock_version   BIGINT       NOT NULL DEFAULT 1,
    CONSTRAINT pk_mfa_totp_credential PRIMARY KEY (id),
    CONSTRAINT uq_mfa_totp_credential_subject UNIQUE (subject),
    CONSTRAINT ck_mfa_totp_status CHECK (status IN ('PENDING', 'ACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mfa_recovery_code (
    id         CHAR(36)   NOT NULL,
    subject    CHAR(36)   NOT NULL,
    code_hash  CHAR(64)   NOT NULL,
    used_at    DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_mfa_recovery_code PRIMARY KEY (id),
    CONSTRAINT uq_mfa_recovery_code UNIQUE (subject, code_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_mfa_recovery_code_subject ON mfa_recovery_code (subject, used_at);
