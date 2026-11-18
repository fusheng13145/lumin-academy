-- =====================================================================
-- V3：申诉、治理措施、举报与站内通知（docs/04 §5 moderation/governance/engagement）
-- MySQL 8.4 方言（映射规则同 V1 头注）；状态语义以 03 §2.4/§6 为准。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. appeal（04 §5 moderation）：同案件最多一条未终结申诉由服务层校验；
--    reviewer 不属于原裁决 actor 集合（回避）由服务层核验
-- ---------------------------------------------------------------------
CREATE TABLE appeal (
    id               CHAR(36)      NOT NULL,
    case_id          CHAR(36)      NOT NULL,
    applicant_id     CHAR(36)      NOT NULL,
    reason           VARCHAR(1000) NOT NULL,
    evidence_refs    JSON          NOT NULL,
    reviewer_id      CHAR(36)      NULL,
    state            VARCHAR(16)   NOT NULL,
    decision_reason  VARCHAR(1000) NULL,
    accepted_due_at  DATETIME(6)   NOT NULL,
    completed_due_at DATETIME(6)   NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    created_by       CHAR(36)      NOT NULL,
    updated_by       CHAR(36)      NOT NULL,
    lock_version     BIGINT        NOT NULL DEFAULT 1,
    deleted_at       DATETIME(6)   NULL,
    CONSTRAINT pk_appeal PRIMARY KEY (id),
    CONSTRAINT fk_appeal_case FOREIGN KEY (case_id) REFERENCES review_case (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_appeal_reviewer FOREIGN KEY (reviewer_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_appeal_state CHECK (state IN ('OPEN','IN_REVIEW','UPHELD','OVERTURNED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_appeal_state_due ON appeal (state, completed_due_at, id);

-- ---------------------------------------------------------------------
-- 2. restriction（04 §5 governance）：临时措施必有 expires_at；
--    FINAL_VIOLATION/CONTRACT_TERMINATED 禁止 REVOKED（03 §2.4，状态机兜底）
-- ---------------------------------------------------------------------
CREATE TABLE restriction (
    id           CHAR(36)      NOT NULL,
    work_id      CHAR(36)      NOT NULL,
    chapter_id   CHAR(36)      NULL,
    kind         VARCHAR(32)   NOT NULL,
    state        VARCHAR(16)   NOT NULL,
    reason       VARCHAR(1000) NOT NULL,
    case_id      CHAR(36)      NOT NULL,
    requested_by CHAR(36)      NOT NULL,
    reviewed_by  CHAR(36)      NULL,
    effective_at DATETIME(6)   NULL,
    expires_at   DATETIME(6)   NULL,
    revoked_at   DATETIME(6)   NULL,
    appeal_id    CHAR(36)      NULL,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL,
    created_by   CHAR(36)      NOT NULL,
    updated_by   CHAR(36)      NOT NULL,
    lock_version BIGINT        NOT NULL DEFAULT 1,
    deleted_at   DATETIME(6)   NULL,
    CONSTRAINT pk_restriction PRIMARY KEY (id),
    CONSTRAINT fk_restriction_work FOREIGN KEY (work_id) REFERENCES work (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_restriction_chapter FOREIGN KEY (chapter_id) REFERENCES chapter (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_restriction_kind CHECK (kind IN
        ('TEMPORARY','FINAL_VIOLATION','CONTRACT_TERMINATED','GUARDIAN_REVOKED')),
    CONSTRAINT ck_restriction_state CHECK (state IN ('PENDING','ACTIVE','REVOKED','REJECTED')),
    -- 04 §5：临时措施必有 expires_at；终局/解约禁止 expires_at（不可伪装自动到期恢复）
    -- 目标范围派生列：WORK 限制覆盖全部章节（INV-03）；供 ACTIVE 聚合查询
    target_scope VARCHAR(8) GENERATED ALWAYS AS
        (CASE WHEN chapter_id IS NULL THEN 'WORK' ELSE 'CHAPTER' END) STORED,
    CONSTRAINT ck_restriction_expires CHECK (
        (kind = 'TEMPORARY' AND expires_at IS NOT NULL)
        OR (kind <> 'TEMPORARY' AND expires_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_restriction_work_state ON restriction (work_id, state, chapter_id);

-- ---------------------------------------------------------------------
-- 3. report（04 §5 governance）：举报关联案件（moderation 建案，source=REPORT）；
--    同主体同目标 24h 内相同 reason_code 去重由服务层校验
-- ---------------------------------------------------------------------
CREATE TABLE report (
    id          CHAR(36)      NOT NULL,
    reporter_id CHAR(36)      NOT NULL,
    work_id     CHAR(36)      NOT NULL,
    chapter_id  CHAR(36)      NULL,
    reason_code VARCHAR(40)   NOT NULL,
    description VARCHAR(1000) NOT NULL,
    case_id     CHAR(36)      NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    created_by  CHAR(36)      NOT NULL,
    updated_by  CHAR(36)      NOT NULL,
    lock_version BIGINT       NOT NULL DEFAULT 1,
    deleted_at  DATETIME(6)   NULL,
    CONSTRAINT pk_report PRIMARY KEY (id),
    CONSTRAINT fk_report_work FOREIGN KEY (work_id) REFERENCES work (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_report_chapter FOREIGN KEY (chapter_id) REFERENCES chapter (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_report_reporter ON report (reporter_id, created_at DESC, id);

-- ---------------------------------------------------------------------
-- 4. notification（04 §5.1 engagement）：U(recipient,source_event,category)
--    幂等；UPDATE 类别为 P1 追更（本阶段不启用）
-- ---------------------------------------------------------------------
CREATE TABLE notification (
    id              CHAR(36)      NOT NULL,
    recipient_id    CHAR(36)      NOT NULL,
    category        VARCHAR(24)   NOT NULL,
    source_event_id CHAR(36)      NULL,
    title           VARCHAR(100)  NOT NULL,
    body            VARCHAR(1000) NOT NULL,
    resource_type   VARCHAR(16)   NOT NULL,
    resource_id     CHAR(36)      NOT NULL,
    read_at         DATETIME(6)   NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    created_by      CHAR(36)      NOT NULL,
    updated_by      CHAR(36)      NOT NULL,
    lock_version    BIGINT        NOT NULL DEFAULT 1,
    deleted_at      DATETIME(6)   NULL,
    CONSTRAINT pk_notification PRIMARY KEY (id),
    CONSTRAINT uq_notification_event UNIQUE (recipient_id, source_event_id, category),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_id)
        REFERENCES user_profile (id) ON DELETE RESTRICT,
    CONSTRAINT ck_notification_category CHECK (category IN
        ('REVIEW_RESULT','SAFETY','GUARDIAN','RIGHTS','UPDATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_notification_recipient ON notification (recipient_id, created_at DESC, id);
