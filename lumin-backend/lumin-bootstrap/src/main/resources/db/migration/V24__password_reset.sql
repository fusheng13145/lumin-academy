-- =====================================================================
-- V24：口令找回 / 重置（双通道，2026-11-20 安全审查 04 项收尾）
--
-- 通道一 EMAIL：请求后立即生成一次性重置码（只存 SHA-256 摘要，30 分钟、单次），
--   经 PasswordResetMailGateway 投递；不把重置码回给请求者（否则等于无凭据改密）。
-- 通道二 CUSTOMER_SERVICE：请求只建工单（AWAITING_OPERATOR），客服/运营核验身份
--   后批准，重置码**一次性回给操作员**（由客服口述给用户），不经邮件。
--
-- 口径：
--   1) 无论登录名是否存在，请求接口一律 200（不可枚举账号）；
--   2) 重置码摘要入库、明文不落库、不写日志；
--   3) 重置成功即撤销该主体全部会话（避免旧令牌继续可用）；
--   4) 重置**不**动 MFA 状态：第二因子必须仍然拦得住（重置本身可能是攻击者发起的）。
--
-- email 列：注册时可选录入（唯一、可空）。没有邮箱的账号只能走客服通道，
-- 这是"邮件通道未接 SMTP 时仍能自救"的最小闭环，不是死代码。
-- =====================================================================

ALTER TABLE password_credential ADD COLUMN email VARCHAR(254) NULL AFTER login_id;
-- 可空唯一：MySQL 多行 NULL 不冲突，同一邮箱只能绑一个账号
CREATE UNIQUE INDEX uq_password_credential_email ON password_credential (email);

CREATE TABLE password_reset_ticket (
    id          CHAR(36)     NOT NULL,
    login_id    VARCHAR(64)  NOT NULL,
    subject     CHAR(36)     NOT NULL,
    channel     VARCHAR(24)  NOT NULL,
    state       VARCHAR(24)  NOT NULL,
    code_hash   CHAR(64)     NULL,
    expires_at  DATETIME(6)  NULL,
    consumed_at DATETIME(6)  NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    created_by  CHAR(36)     NOT NULL,
    updated_by  CHAR(36)     NOT NULL,
    lock_version BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT pk_password_reset_ticket PRIMARY KEY (id),
    CONSTRAINT ck_password_reset_channel
        CHECK (channel IN ('EMAIL', 'CUSTOMER_SERVICE')),
    CONSTRAINT ck_password_reset_state CHECK (state IN
        ('AWAITING_OPERATOR', 'PENDING', 'DELIVERY_FAILED', 'CONSUMED', 'REJECTED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_password_reset_login ON password_reset_ticket (login_id, state);
CREATE INDEX idx_password_reset_state ON password_reset_ticket (state, created_at, id);
