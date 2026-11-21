-- =====================================================================
-- V19：用户屏蔽/拉黑（docs/13 UGC-043 一阶段；互动安全网关——UGC-042 评论
-- 与 UGC-044 关注的执行依据）。U(blocker, blocked)；不能拉黑自己；
-- 双向解列：拉黑是单向关系（A 拉黑 B 不含 B 拉黑 A）。
-- 执行面：foundation/port.InteractionGatePort（评论/关注/通知过滤随
-- UGC-042/044 接线）；本阶段交付数据面 + 端口 + 审计。
-- =====================================================================

CREATE TABLE user_block (
    id         CHAR(36)     NOT NULL,
    blocker_id CHAR(36)     NOT NULL,
    blocked_id CHAR(36)     NOT NULL,
    reason     VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    created_by CHAR(36)    NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by CHAR(36)    NOT NULL,
    lock_version BIGINT    NOT NULL DEFAULT 1,
    CONSTRAINT pk_user_block PRIMARY KEY (id),
    CONSTRAINT uq_user_block_pair UNIQUE (blocker_id, blocked_id),
    CONSTRAINT ck_blocker_ne_blocked CHECK (blocker_id <> blocked_id),
    CONSTRAINT fk_block_blocker FOREIGN KEY (blocker_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_block_blocked FOREIGN KEY (blocked_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_block_blocker ON user_block (blocker_id, created_at DESC, id);
CREATE INDEX idx_block_blocked ON user_block (blocked_id);
