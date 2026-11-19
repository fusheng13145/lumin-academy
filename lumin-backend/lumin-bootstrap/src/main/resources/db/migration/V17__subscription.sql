-- =====================================================================
-- V17：追更订阅与聚合通知去重（docs/04 §6.1 P1 原文落地；docs/13 UGC-036）：
-- subscription U(user_id, work_id)；收藏≠订阅（bookmark 不产生订阅行）；
-- timezone_id 为 §6.1 之外的增加列（UGC-036「时区与天然日」要求：
-- 免打扰窗口与聚合自然日按接收人时区解释，docs/04 已同步登记）。
-- notification_digest U(recipient_id, work_id, local_date)——UGC-037 接
-- NotificationDigestPolicy（T34）时写入；修订/作者公告不触发 UPDATE。
-- =====================================================================

CREATE TABLE subscription (
    id           CHAR(36)    NOT NULL,
    user_id      CHAR(36)    NOT NULL,
    work_id      CHAR(36)    NOT NULL,
    enabled      TINYINT(1)  NOT NULL DEFAULT 1,
    quiet_start  TIME        NOT NULL DEFAULT '22:00:00',
    quiet_end    TIME        NOT NULL DEFAULT '08:00:00',
    push_enabled TINYINT(1)  NOT NULL DEFAULT 0,
    timezone_id  VARCHAR(64) NOT NULL DEFAULT 'UTC',
    created_at DATETIME(6) NOT NULL,
    created_by CHAR(36)    NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by CHAR(36)    NOT NULL,
    lock_version BIGINT    NOT NULL DEFAULT 1,
    CONSTRAINT pk_subscription PRIMARY KEY (id),
    CONSTRAINT uq_subscription_user_work UNIQUE (user_id, work_id),
    CONSTRAINT fk_subscription_user FOREIGN KEY (user_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_work FOREIGN KEY (work_id) REFERENCES work (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_subscription_user ON subscription (user_id, created_at DESC, id);

CREATE TABLE notification_digest (
    id                CHAR(36)    NOT NULL,
    recipient_id      CHAR(36)    NOT NULL,
    work_id           CHAR(36)    NOT NULL,
    local_date        DATE        NOT NULL,
    latest_chapter_id CHAR(36)    NOT NULL,
    sent_at           DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by CHAR(36)    NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by CHAR(36)    NOT NULL,
    lock_version BIGINT    NOT NULL DEFAULT 1,
    CONSTRAINT pk_notification_digest PRIMARY KEY (id),
    CONSTRAINT uq_digest_recipient_work_date UNIQUE (recipient_id, work_id, local_date),
    CONSTRAINT fk_digest_recipient FOREIGN KEY (recipient_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_digest_work FOREIGN KEY (work_id) REFERENCES work (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_digest_recipient_date ON notification_digest (recipient_id, local_date);
