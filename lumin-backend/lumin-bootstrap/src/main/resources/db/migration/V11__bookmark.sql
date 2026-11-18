-- =====================================================================
-- V11：收藏（docs/04 §6.1 bookmark 原文落地；docs/13 UGC-035 M1）：
-- 收藏 = 个人书单，无通知语义（订阅/追更走 subscription，UGC-036）。
-- U(user_id, work_id)；跨账号隔离由按主体查询保证。
-- =====================================================================

CREATE TABLE bookmark (
    id         CHAR(36)    NOT NULL,
    user_id    CHAR(36)    NOT NULL,
    work_id    CHAR(36)    NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by CHAR(36)    NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by CHAR(36)    NOT NULL,
    lock_version BIGINT    NOT NULL DEFAULT 1,
    CONSTRAINT pk_bookmark PRIMARY KEY (id),
    CONSTRAINT uq_bookmark_user_work UNIQUE (user_id, work_id),
    CONSTRAINT fk_bookmark_user FOREIGN KEY (user_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_bookmark_work FOREIGN KEY (work_id) REFERENCES work (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_bookmark_user ON bookmark (user_id, created_at DESC, id);
