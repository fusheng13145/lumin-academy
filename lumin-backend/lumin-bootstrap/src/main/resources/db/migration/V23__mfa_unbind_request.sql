-- =====================================================================
-- V23：二次验证（TOTP）运维解绑（2026-11-20 安全审查 05 项收尾）
--
-- 背景：设备丢失 / 换机后无法出示验证码，此前没有任何合法出口（ACTIVE 不许自行重登记
-- 是刻意的，但没有解绑流程就等于用户永久失去账号）。这里补的是**人工工单**：
-- 申请人登录后提工单（可用恢复码过挑战）→ OPERATOR/ADMIN 核验身份后批准 →
-- 种子置 REVOKED、恢复码全部作废、该主体全部会话撤销，本人重新登记。
--
-- 为什么不给"自己解绑"的接口：那等于给会话劫持者一条移除第二因子的捷径。
-- =====================================================================

-- 状态机扩一档：REVOKED（已解绑，等待重新登记；行保留以留痕）
ALTER TABLE mfa_totp_credential DROP CHECK ck_mfa_totp_status;
ALTER TABLE mfa_totp_credential
    ADD CONSTRAINT ck_mfa_totp_status CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED'));

CREATE TABLE mfa_unbind_request (
    id           CHAR(36)     NOT NULL,
    subject      CHAR(36)     NOT NULL,
    reason       VARCHAR(500) NOT NULL,
    state        VARCHAR(16)  NOT NULL,
    requested_at DATETIME(6)  NOT NULL,
    reviewed_by  CHAR(36)     NULL,
    reviewed_at  DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    created_by   CHAR(36)     NOT NULL,
    updated_by   CHAR(36)     NOT NULL,
    lock_version BIGINT       NOT NULL DEFAULT 1,
    -- 同一主体同时只能有一张 PENDING 工单（生成列唯一索引；终态释放槽位）
    open_slot    CHAR(36)     GENERATED ALWAYS AS
        (IF(state = 'PENDING', subject, NULL)) STORED,
    CONSTRAINT pk_mfa_unbind_request PRIMARY KEY (id),
    CONSTRAINT ck_mfa_unbind_state CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT uq_mfa_unbind_open UNIQUE (open_slot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_mfa_unbind_state ON mfa_unbind_request (state, requested_at, id);
