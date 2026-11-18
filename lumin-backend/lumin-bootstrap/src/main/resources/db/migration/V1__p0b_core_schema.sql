-- =====================================================================
-- 启明书院 · P0-B 增量四：核心发布链与支撑表（V1）
-- 依据：docs/04-数据库设计说明书 §1/§3/§4/§5（表语义权威）+ §1 修订注记的 MySQL 映射
-- 审定：2026-11-16 Owner 裁决「数据库使用 MySQL 8.4 LTS」
--
-- 方言映射（与 docs/04 §1 修订注记一致）：
--   TIMESTAMPTZ → DATETIME(6)（统一存 UTC；连接串 connectionTimeZone=UTC
--                + forceConnectionTimeZoneToSession=true 强制会话时区）
--   JSONB → JSON；BYTEA → VARBINARY；UUID → CHAR(36)（小写规范形）
--   部分唯一索引 / NULLS NOT DISTINCT → STORED 生成列 + 唯一索引模拟
--   TIMESTAMP（2038 上限）一律不用；VARCHAR 枚举 → CHECK 白名单（MySQL 8.0.16+ 强制）
--   外键 ON DELETE RESTRICT；无触发器 / 存储过程；utf8mb4 / InnoDB
--
-- 通用字段（docs/04 §1.1）：所有业务表 id CHAR(36) 主键、created_at/updated_at
--   DATETIME(6) 非空、created_by/updated_by CHAR(36) 非空、lock_version BIGINT
--   非空默认 1；普通业务表含 deleted_at（审计、裁决、版本、事件、幂等记录、
--   证据报告、配置修订不含——追加/版本化，不可删除）。
-- 本轮（P0-B 增量四）16 表：user_profile、category、work、chapter、draft、
--   content_version、publication_workflow、publication_pointer、review_case、
--   machine_report、review_decision、config_revision、outbox_event、
--   inbox_receipt、idempotency_record、audit_record。
-- 未含表（后续增量）：age_verification、guardian_consent、role_assignment、
--   role_grant、rights_request、content_asset、asset_upload、appeal、report、
--   restriction、legal_hold、proposal_review、notification、reading_progress、
--   P1 三表（bookmark/subscription/notification_digest）、billing 六表。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. user_profile（04 §3 identity）
-- ---------------------------------------------------------------------
CREATE TABLE user_profile (
    id                 CHAR(36)     NOT NULL,
    oidc_issuer        VARCHAR(200) NOT NULL,
    oidc_subject       VARCHAR(200) NOT NULL,
    nickname           VARCHAR(40)  NOT NULL,
    birth_date_cipher  VARBINARY(512) NULL,
    declared_age_band  VARCHAR(2)   NOT NULL,
    verified_age_band  VARCHAR(2)   NULL,
    effective_age_band VARCHAR(2)   NOT NULL,
    age_verified_at    DATETIME(6)  NULL,
    account_status     VARCHAR(16)  NOT NULL,
    policy_revision    BIGINT       NOT NULL,
    closed_at          DATETIME(6)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    created_by         CHAR(36)     NOT NULL,
    updated_by         CHAR(36)     NOT NULL,
    lock_version       BIGINT       NOT NULL DEFAULT 1,
    deleted_at         DATETIME(6)  NULL,
    CONSTRAINT pk_user_profile PRIMARY KEY (id),
    CONSTRAINT uq_user_profile_issuer_subject UNIQUE (oidc_issuer, oidc_subject),
    CONSTRAINT ck_user_profile_declared CHECK (declared_age_band IN ('C0','C1','C2','C3','C4')),
    CONSTRAINT ck_user_profile_verified CHECK (verified_age_band IS NULL OR verified_age_band IN ('C0','C1','C2','C3','C4')),
    CONSTRAINT ck_user_profile_effective CHECK (effective_age_band IN ('C0','C1','C2','C3','C4')),
    CONSTRAINT ck_user_profile_status CHECK (account_status IN ('ACTIVE','SUSPENDED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 2. category（04 §4 catalog）；同父级 name 唯一（NULLS NOT DISTINCT →
--    parent_slot 生成列归一根分类）；禁止父子环与历史引用完整性由服务层校验
-- ---------------------------------------------------------------------
CREATE TABLE category (
    id          CHAR(36)    NOT NULL,
    parent_id   CHAR(36)    NULL,
    name        VARCHAR(40) NOT NULL,
    sort_order  INT         NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    created_by  CHAR(36)    NOT NULL,
    updated_by  CHAR(36)    NOT NULL,
    lock_version BIGINT     NOT NULL DEFAULT 1,
    deleted_at  DATETIME(6) NULL,
    parent_slot CHAR(36) GENERATED ALWAYS AS (COALESCE(parent_id, 'ROOT')) STORED,
    CONSTRAINT pk_category PRIMARY KEY (id),
    CONSTRAINT uq_category_parent_name UNIQUE (parent_slot, name),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_category_name_len CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 40)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_category_parent_sort ON category (parent_id, sort_order, id);

-- ---------------------------------------------------------------------
-- 3. work（04 §4 catalog）
-- ---------------------------------------------------------------------
CREATE TABLE work (
    id               CHAR(36)    NOT NULL,
    author_id        CHAR(36)    NOT NULL,
    business_state   VARCHAR(16) NOT NULL,
    prior_state      VARCHAR(16) NULL,
    copyright_status VARCHAR(16) NOT NULL,
    next_chapter_no  INT         NOT NULL,
    completed_at     DATETIME(6) NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    created_by       CHAR(36)    NOT NULL,
    updated_by       CHAR(36)    NOT NULL,
    lock_version     BIGINT      NOT NULL DEFAULT 1,
    deleted_at       DATETIME(6) NULL,
    CONSTRAINT pk_work PRIMARY KEY (id),
    CONSTRAINT fk_work_author FOREIGN KEY (author_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_work_state CHECK (business_state IN ('DRAFT','ACTIVE','COMPLETED','REMOVED','TERMINATED')),
    CONSTRAINT ck_work_copyright CHECK (copyright_status IN ('VALID','TERMINATED')),
    CONSTRAINT ck_work_next_chapter CHECK (next_chapter_no >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_work_author_created ON work (author_id, created_at DESC, id);
CREATE INDEX idx_work_state_updated ON work (business_state, updated_at DESC, id);

-- ---------------------------------------------------------------------
-- 4. chapter（04 §4 catalog）：章节禁 COMPLETED（03 §2.1）
-- ---------------------------------------------------------------------
CREATE TABLE chapter (
    id             CHAR(36)    NOT NULL,
    work_id        CHAR(36)    NOT NULL,
    sequence_no    INT         NOT NULL,
    business_state VARCHAR(16) NOT NULL,
    prior_state    VARCHAR(16) NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    created_by     CHAR(36)    NOT NULL,
    updated_by     CHAR(36)    NOT NULL,
    lock_version   BIGINT      NOT NULL DEFAULT 1,
    deleted_at     DATETIME(6) NULL,
    CONSTRAINT pk_chapter PRIMARY KEY (id),
    CONSTRAINT uq_chapter_work_sequence UNIQUE (work_id, sequence_no),
    CONSTRAINT fk_chapter_work FOREIGN KEY (work_id) REFERENCES work (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_chapter_sequence CHECK (sequence_no > 0),
    -- 03 §2.1：章节禁 COMPLETED（白名单直接排除该值）
    CONSTRAINT ck_chapter_state CHECK (business_state IN ('DRAFT','ACTIVE','REMOVED','TERMINATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_chapter_work_seq ON chapter (work_id, sequence_no, id);

-- ---------------------------------------------------------------------
-- 5. draft（04 §4 authoring）：WORK/CHAPTER 形态 CHECK；同目标最多一个
--    EDITABLE 草稿（target_key + editable_slot 生成列模拟部分唯一）
-- ---------------------------------------------------------------------
CREATE TABLE draft (
    id              CHAR(36)      NOT NULL,
    work_id         CHAR(36)      NOT NULL,
    chapter_id      CHAR(36)      NULL,
    target_type     VARCHAR(8)    NOT NULL,
    base_version_id CHAR(36)      NULL,
    title           VARCHAR(100)  NOT NULL,
    synopsis        VARCHAR(2000) NULL,
    category_id     CHAR(36)      NULL,
    tags            JSON          NOT NULL,
    cover_asset_id  CHAR(36)      NULL,
    body_text       MEDIUMTEXT    NULL,
    state           VARCHAR(16)   NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    created_by      CHAR(36)      NOT NULL,
    updated_by      CHAR(36)      NOT NULL,
    lock_version    BIGINT        NOT NULL DEFAULT 1,
    deleted_at      DATETIME(6)   NULL,
    target_key      CHAR(96) GENERATED ALWAYS AS
        (CONCAT(target_type, ':', work_id, ':', COALESCE(chapter_id, '-'))) STORED,
    editable_slot   TINYINT GENERATED ALWAYS AS
        (CASE WHEN state = 'EDITABLE' THEN 1 ELSE NULL END) STORED,
    CONSTRAINT pk_draft PRIMARY KEY (id),
    CONSTRAINT uq_draft_target_editable UNIQUE (target_key, editable_slot),
    CONSTRAINT fk_draft_work FOREIGN KEY (work_id) REFERENCES work (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_draft_chapter FOREIGN KEY (chapter_id) REFERENCES chapter (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_draft_category FOREIGN KEY (category_id) REFERENCES category (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_draft_target CHECK (target_type IN ('WORK','CHAPTER')),
    CONSTRAINT ck_draft_state CHECK (state IN ('EDITABLE','SUBMITTED','DISCARDED')),
    CONSTRAINT ck_draft_tags CHECK (JSON_LENGTH(tags) <= 5),
    -- 04 §4 draft：WORK 必有 synopsis/category 且无正文、无章节；
    -- CHAPTER 必有正文与章节（05 §4.1：synopsis/categoryId 禁止）
    CONSTRAINT ck_draft_shape CHECK (
        (target_type = 'WORK' AND chapter_id IS NULL
            AND synopsis IS NOT NULL AND category_id IS NOT NULL AND body_text IS NULL)
        OR
        (target_type = 'CHAPTER' AND chapter_id IS NOT NULL
            AND body_text IS NOT NULL AND synopsis IS NULL AND category_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_draft_work_state ON draft (work_id, state, updated_at DESC, id);

-- ---------------------------------------------------------------------
-- 6. content_version（04 §4 authoring）：内容不可变（正文/元数据字段仅由
--    状态机字段更新并审计）；WORK/CHAPTER 各自 revision 唯一；
--    CHAPTER 正文对象字段全非空
-- ---------------------------------------------------------------------
CREATE TABLE content_version (
    id                CHAR(36)      NOT NULL,
    work_id           CHAR(36)      NOT NULL,
    chapter_id        CHAR(36)      NULL,
    target_type       VARCHAR(8)    NOT NULL,
    revision_no       BIGINT        NOT NULL,
    source_draft_id   CHAR(36)      NULL,
    title             VARCHAR(100)  NOT NULL,
    synopsis          VARCHAR(2000) NULL,
    category_id       CHAR(36)      NULL,
    tags              JSON          NOT NULL,
    cover_asset_id    CHAR(36)      NULL,
    object_key        VARCHAR(300)  NULL,
    body_sha256       CHAR(64)      NULL,
    material_snapshot JSON          NOT NULL,
    material_sha256   CHAR(64)      NOT NULL,
    byte_length       INT           NULL,
    state             VARCHAR(16)   NOT NULL,
    grade             VARCHAR(8)    NULL,
    policy_version    BIGINT        NOT NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    created_by        CHAR(36)      NOT NULL,
    updated_by        CHAR(36)      NOT NULL,
    lock_version      BIGINT        NOT NULL DEFAULT 1,
    rev_key           CHAR(96) GENERATED ALWAYS AS
        (CONCAT(target_type, ':', work_id, ':', COALESCE(chapter_id, '-'))) STORED,
    CONSTRAINT pk_content_version PRIMARY KEY (id),
    CONSTRAINT uq_content_version_source_draft UNIQUE (source_draft_id),
    CONSTRAINT uq_content_version_target_revision UNIQUE (rev_key, revision_no),
    CONSTRAINT fk_content_version_work FOREIGN KEY (work_id) REFERENCES work (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_content_version_chapter FOREIGN KEY (chapter_id) REFERENCES chapter (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_content_version_source_draft FOREIGN KEY (source_draft_id)
        REFERENCES draft (id) ON DELETE RESTRICT,
    CONSTRAINT fk_content_version_category FOREIGN KEY (category_id) REFERENCES category (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_content_version_target CHECK (target_type IN ('WORK','CHAPTER')),
    CONSTRAINT ck_content_version_state CHECK (state IN ('SUBMITTED','APPROVED','REJECTED','WITHDRAWN')),
    CONSTRAINT ck_content_version_grade CHECK (grade IS NULL OR grade IN ('G','PG_12','PG_16','A')),
    CONSTRAINT ck_content_version_tags CHECK (JSON_LENGTH(tags) <= 5),
    -- 正文版本仅属目标：CHAPTER 对象字段全非空；WORK 无正文对象
    CONSTRAINT ck_content_version_body CHECK (
        (target_type = 'WORK' AND object_key IS NULL AND body_sha256 IS NULL AND byte_length IS NULL)
        OR
        (target_type = 'CHAPTER' AND object_key IS NOT NULL AND body_sha256 IS NOT NULL
            AND byte_length IS NOT NULL)
    ),
    -- 04 §5.2 末段：GRADE 仅随 PASS/LABEL/LIMIT 类裁决写入，值域受 CHECK 保护
    CONSTRAINT ck_content_version_sha CHECK (body_sha256 IS NULL OR body_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_content_version_material CHECK (material_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_content_version_work ON content_version (work_id, created_at, id);

-- ---------------------------------------------------------------------
-- 7. publication_workflow（04 §4 publication）：version_id 唯一（INV-01：
--    一份不可变版本只绑定一个发布流程）；同目标非终态部分唯一（open_slot）
-- ---------------------------------------------------------------------
CREATE TABLE publication_workflow (
    id               CHAR(36)    NOT NULL,
    version_id       CHAR(36)    NOT NULL,
    target_type      VARCHAR(8)  NOT NULL,
    target_id        CHAR(36)    NOT NULL,
    state            VARCHAR(20) NOT NULL,
    policy_version   BIGINT      NOT NULL,
    case_id          CHAR(36)    NULL,
    suspended_reason VARCHAR(40) NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    created_by       CHAR(36)    NOT NULL,
    updated_by       CHAR(36)    NOT NULL,
    lock_version     BIGINT      NOT NULL DEFAULT 1,
    target_key       CHAR(96) GENERATED ALWAYS AS (CONCAT(target_type, ':', target_id)) STORED,
    open_slot        TINYINT GENERATED ALWAYS AS
        (CASE WHEN state IN ('SUBMITTED','AUTO_REVIEW','HUMAN_REVIEW','SUSPENDED')
              THEN 1 ELSE NULL END) STORED,
    CONSTRAINT pk_publication_workflow PRIMARY KEY (id),
    CONSTRAINT uq_workflow_version UNIQUE (version_id),
    CONSTRAINT uq_workflow_target_open UNIQUE (target_key, open_slot),
    CONSTRAINT fk_workflow_version FOREIGN KEY (version_id) REFERENCES content_version (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_workflow_target CHECK (target_type IN ('WORK','CHAPTER')),
    CONSTRAINT ck_workflow_state CHECK (state IN ('SUBMITTED','AUTO_REVIEW','HUMAN_REVIEW','APPROVED','REJECTED','SUSPENDED','CANCELLED')),
    -- 03 §2.3：SUSPENDED 必带原因、非 SUSPENDED 不得残留（与 WorkflowSuspension 一致）
    CONSTRAINT ck_workflow_suspension CHECK (
        (state = 'SUSPENDED' AND suspended_reason IS NOT NULL)
        OR (state <> 'SUSPENDED' AND suspended_reason IS NULL)
    ),
    CONSTRAINT ck_workflow_reason CHECK (suspended_reason IS NULL OR suspended_reason IN ('MACHINE_TIMEOUT','CONTRACT_INVALID','BUDGET_EXHAUSTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_workflow_state_updated ON publication_workflow (state, updated_at, id);
-- case_id 与 review_case 互链在服务事务内校验，不建循环立即外键（04 §4 原文）

-- ---------------------------------------------------------------------
-- 8. publication_pointer（04 §4 publication）：U(target_type,target_id)
--    （INV-02：公开指针每目标唯一）；publication_revision 单调由服务层保证
-- ---------------------------------------------------------------------
CREATE TABLE publication_pointer (
    id                   CHAR(36)    NOT NULL,
    target_type          VARCHAR(8)  NOT NULL,
    target_id            CHAR(36)    NOT NULL,
    version_id           CHAR(36)    NOT NULL,
    published_at         DATETIME(6) NOT NULL,
    publication_revision BIGINT      NOT NULL,
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    created_by           CHAR(36)    NOT NULL,
    updated_by           CHAR(36)    NOT NULL,
    lock_version         BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT pk_publication_pointer PRIMARY KEY (id),
    CONSTRAINT uq_pointer_target UNIQUE (target_type, target_id),
    CONSTRAINT fk_pointer_version FOREIGN KEY (version_id) REFERENCES content_version (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_pointer_target CHECK (target_type IN ('WORK','CHAPTER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_pointer_published ON publication_pointer (published_at DESC, id);

-- ---------------------------------------------------------------------
-- 9. review_case（04 §5 moderation）：阶段 1/2/3；租约与回避由服务层核验；
--    queue_id 为逻辑队列标识（本设计无独立队列表，不建外键）
-- ---------------------------------------------------------------------
CREATE TABLE review_case (
    id               CHAR(36)    NOT NULL,
    version_id       CHAR(36)    NULL,
    workflow_id      CHAR(36)    NULL,
    work_id          CHAR(36)    NOT NULL,
    chapter_id       CHAR(36)    NULL,
    source           VARCHAR(16) NOT NULL,
    risk             VARCHAR(8)  NOT NULL,
    priority         VARCHAR(2)  NOT NULL,
    state            VARCHAR(20) NOT NULL,
    queue_id         CHAR(36)    NOT NULL,
    review_stage     SMALLINT    NOT NULL,
    assignee_id      CHAR(36)    NULL,
    lease_expires_at DATETIME(6) NULL,
    response_due_at  DATETIME(6) NOT NULL,
    completion_due_at DATETIME(6) NOT NULL,
    policy_version   BIGINT      NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    created_by       CHAR(36)    NOT NULL,
    updated_by       CHAR(36)    NOT NULL,
    lock_version     BIGINT      NOT NULL DEFAULT 1,
    deleted_at       DATETIME(6) NULL,
    CONSTRAINT pk_review_case PRIMARY KEY (id),
    CONSTRAINT fk_case_version FOREIGN KEY (version_id) REFERENCES content_version (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_case_workflow FOREIGN KEY (workflow_id) REFERENCES publication_workflow (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_case_work FOREIGN KEY (work_id) REFERENCES work (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_case_chapter FOREIGN KEY (chapter_id) REFERENCES chapter (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_case_assignee FOREIGN KEY (assignee_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_case_source CHECK (source IN ('SUBMISSION','REPORT','APPEAL','INSPECTION','LEGAL')),
    CONSTRAINT ck_case_risk CHECK (risk IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT ck_case_priority CHECK (priority IN ('P0','P1','P2','P3')),
    CONSTRAINT ck_case_state CHECK (state IN ('QUEUED','CLAIMED','WAITING_SECOND','DECIDED')),
    CONSTRAINT ck_case_stage CHECK (review_stage IN (1,2,3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_case_queue_state ON review_case (queue_id, state, priority, completion_due_at, id);
CREATE INDEX idx_case_work_created ON review_case (work_id, created_at, id);

-- ---------------------------------------------------------------------
-- 10. machine_report（04 §5 moderation）：不可变证据，无 deleted_at；
--     grade 配对规则由 CHECK 兜底（05 §4.3）；confidence NUMERIC(5,4)
-- ---------------------------------------------------------------------
CREATE TABLE machine_report (
    id                 CHAR(36)      NOT NULL,
    case_id            CHAR(36)      NOT NULL,
    provider           VARCHAR(40)   NOT NULL,
    model_version      VARCHAR(80)   NOT NULL,
    material_sha256    CHAR(64)      NOT NULL,
    grade              VARCHAR(8)    NULL,
    policy_version     BIGINT        NOT NULL,
    risk               VARCHAR(8)    NOT NULL,
    flags              JSON          NOT NULL,
    suggested_decision VARCHAR(16)   NOT NULL,
    confidence         DECIMAL(5,4)  NOT NULL,
    evidence_refs      JSON          NOT NULL,
    input_tokens       INT           NOT NULL,
    output_tokens      INT           NOT NULL,
    duration_ms        INT           NOT NULL,
    completed_at       DATETIME(6)   NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    created_by         CHAR(36)      NOT NULL,
    updated_by         CHAR(36)      NOT NULL,
    lock_version       BIGINT        NOT NULL DEFAULT 1,
    CONSTRAINT pk_machine_report PRIMARY KEY (id),
    CONSTRAINT fk_machine_report_case FOREIGN KEY (case_id) REFERENCES review_case (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_report_risk CHECK (risk IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT ck_report_decision CHECK (suggested_decision IN ('PASS','LABEL','LIMIT','REVISE','HIDE','REMOVE','BAN','ESCALATE')),
    CONSTRAINT ck_report_grade CHECK (grade IS NULL OR grade IN ('G','PG_12','PG_16','A')),
    -- 05 §4.3：PASS/LABEL/LIMIT 必带 grade，其余禁止 grade（含 null）
    CONSTRAINT ck_report_grade_pairing CHECK (
        (suggested_decision IN ('PASS','LABEL','LIMIT') AND grade IS NOT NULL)
        OR (suggested_decision NOT IN ('PASS','LABEL','LIMIT') AND grade IS NULL)
    ),
    CONSTRAINT ck_report_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_report_tokens CHECK (input_tokens >= 0 AND input_tokens <= 40000
        AND output_tokens >= 0 AND output_tokens <= 2000
        AND duration_ms >= 0 AND duration_ms <= 120000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_report_case_created ON machine_report (case_id, created_at, id);

-- ---------------------------------------------------------------------
-- 11. review_decision（04 §5 moderation）：append-only，无 deleted_at；
--     U(case_id,actor_id,stage)；grade 配对 CHECK 兜底
-- ---------------------------------------------------------------------
CREATE TABLE review_decision (
    id             CHAR(36)      NOT NULL,
    case_id        CHAR(36)      NOT NULL,
    actor_id       CHAR(36)      NOT NULL,
    actor_type     VARCHAR(8)    NOT NULL,
    decision       VARCHAR(16)   NOT NULL,
    grade          VARCHAR(8)    NULL,
    reason_code    VARCHAR(40)   NOT NULL,
    reason         VARCHAR(1000) NOT NULL,
    evidence_refs  JSON          NOT NULL,
    stage          SMALLINT      NOT NULL,
    policy_version BIGINT        NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    created_by     CHAR(36)      NOT NULL,
    updated_by     CHAR(36)      NOT NULL,
    lock_version   BIGINT        NOT NULL DEFAULT 1,
    CONSTRAINT pk_review_decision PRIMARY KEY (id),
    CONSTRAINT uq_decision_case_actor_stage UNIQUE (case_id, actor_id, stage),
    CONSTRAINT fk_decision_case FOREIGN KEY (case_id) REFERENCES review_case (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_decision_actor_type CHECK (actor_type IN ('HUMAN','SYSTEM')),
    CONSTRAINT ck_decision_decision CHECK (decision IN ('PASS','LABEL','LIMIT','REVISE','HIDE','REMOVE','BAN','ESCALATE')),
    CONSTRAINT ck_decision_grade CHECK (grade IS NULL OR grade IN ('G','PG_12','PG_16','A')),
    CONSTRAINT ck_decision_grade_pairing CHECK (
        (decision IN ('PASS','LABEL','LIMIT') AND grade IS NOT NULL)
        OR (decision NOT IN ('PASS','LABEL','LIMIT') AND grade IS NULL)
    ),
    CONSTRAINT ck_decision_stage CHECK (stage IN (1,2,3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 12. config_revision（04 §5.1 configuration）：版本化不可删（无 deleted_at）；
--     U(config_key,revision)；双槽唯一（full_slot / gray_slot 生成列）；
--     申请人与审批人不同；rollout 1..10000
-- ---------------------------------------------------------------------
CREATE TABLE config_revision (
    id                   CHAR(36)     NOT NULL,
    config_key           VARCHAR(40)  NOT NULL,
    revision             BIGINT       NOT NULL,
    base_revision        BIGINT       NOT NULL,
    schema_version       INT          NOT NULL,
    payload              JSON         NOT NULL,
    state                VARCHAR(16)  NOT NULL,
    reason               VARCHAR(500) NOT NULL,
    requested_by         CHAR(36)     NOT NULL,
    approved_by          CHAR(36)     NULL,
    effective_at         DATETIME(6)  NOT NULL,
    rollout_basis_points INT          NOT NULL,
    rollback_of          CHAR(36)     NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    created_by           CHAR(36)     NOT NULL,
    updated_by           CHAR(36)     NOT NULL,
    lock_version         BIGINT       NOT NULL DEFAULT 1,
    full_slot            VARCHAR(40) GENERATED ALWAYS AS
        (CASE WHEN state = 'ACTIVE' AND rollout_basis_points = 10000
              THEN config_key ELSE NULL END) STORED,
    gray_slot            VARCHAR(40) GENERATED ALWAYS AS
        (CASE WHEN state = 'ACTIVE' AND rollout_basis_points BETWEEN 1 AND 9999
              THEN config_key ELSE NULL END) STORED,
    CONSTRAINT pk_config_revision PRIMARY KEY (id),
    CONSTRAINT uq_config_key_revision UNIQUE (config_key, revision),
    CONSTRAINT uq_config_full_slot UNIQUE (full_slot),
    CONSTRAINT uq_config_gray_slot UNIQUE (gray_slot),
    CONSTRAINT fk_config_rollback_of FOREIGN KEY (rollback_of) REFERENCES config_revision (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_config_key CHECK (config_key IN ('REVIEW_ROUTING','ACCESS_POLICY','NOTIFICATION_POLICY','CONTENT_CATALOG')),
    CONSTRAINT ck_config_state CHECK (state IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','CANCELLED','ACTIVE','SUPERSEDED')),
    CONSTRAINT ck_config_rollout CHECK (rollout_basis_points BETWEEN 1 AND 10000),
    -- 05 §4.2：申请人与审批人不同
    CONSTRAINT ck_config_reviewer CHECK (approved_by IS NULL OR approved_by <> requested_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_config_state ON config_revision (config_key, state, revision);

-- ---------------------------------------------------------------------
-- 13. outbox_event（04 §5.1，各域经 foundation 端口）：无 deleted_at；
--     U(aggregate_type,aggregate_id,aggregate_revision,event_type)；
--     待投递部分索引 → pending_slot 生成列
-- ---------------------------------------------------------------------
CREATE TABLE outbox_event (
    id                 CHAR(36)    NOT NULL,
    event_id           CHAR(36)    NOT NULL,
    aggregate_type     VARCHAR(40) NOT NULL,
    aggregate_id       CHAR(36)    NOT NULL,
    aggregate_revision BIGINT      NOT NULL,
    event_type         VARCHAR(80) NOT NULL,
    schema_version     INT         NOT NULL,
    payload            JSON        NOT NULL,
    occurred_at        DATETIME(6) NOT NULL,
    delivered_at       DATETIME(6) NULL,
    attempts           INT         NOT NULL DEFAULT 0,
    next_attempt_at    DATETIME(6) NULL,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    created_by         CHAR(36)    NOT NULL,
    updated_by         CHAR(36)    NOT NULL,
    lock_version       BIGINT      NOT NULL DEFAULT 1,
    pending_slot       TINYINT GENERATED ALWAYS AS
        (CASE WHEN delivered_at IS NULL THEN 1 ELSE NULL END) STORED,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    CONSTRAINT uq_outbox_aggregate_revision UNIQUE (aggregate_type, aggregate_id, aggregate_revision, event_type),
    CONSTRAINT ck_outbox_schema CHECK (schema_version = 1),
    CONSTRAINT ck_outbox_revision CHECK (aggregate_revision >= 1),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_outbox_pending ON outbox_event (pending_slot, next_attempt_at, id);

-- ---------------------------------------------------------------------
-- 14. inbox_receipt（04 §5.1，消费域）：复合主键 U(consumer_name,event_id)
-- ---------------------------------------------------------------------
CREATE TABLE inbox_receipt (
    consumer_name VARCHAR(80)  NOT NULL,
    event_id      CHAR(36)     NOT NULL,
    processed_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_inbox_receipt PRIMARY KEY (consumer_name, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 15. idempotency_record（04 §5.1，foundation 适配器）：无 deleted_at；
--     复合主键 U(subject_id,method,path,key)；响应 24h 过期由服务层判定
-- ---------------------------------------------------------------------
CREATE TABLE idempotency_record (
    subject_id     CHAR(36)     NOT NULL,
    method         VARCHAR(8)   NOT NULL,
    path           VARCHAR(300) NOT NULL,
    `key`          CHAR(36)     NOT NULL,
    request_digest CHAR(64)     NOT NULL,
    response_code  INT          NULL,
    response_body  JSON         NULL,
    expires_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (subject_id, method, path, `key`),
    CONSTRAINT ck_idempotency_method CHECK (method IN ('POST','PUT','PATCH'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- 16. audit_record（04 §5.1 audit）：append-only，无 deleted_at；
--     不记正文/令牌/证件；digest 用于定期签名归档（WORM）
-- ---------------------------------------------------------------------
CREATE TABLE audit_record (
    id             CHAR(36)      NOT NULL,
    actor_id       CHAR(36)      NOT NULL,
    role_context   VARCHAR(32)   NOT NULL,
    action         VARCHAR(80)   NOT NULL,
    resource_type  VARCHAR(40)   NOT NULL,
    resource_id    CHAR(36)      NOT NULL,
    before_redacted JSON         NULL,
    after_redacted JSON          NULL,
    reason         VARCHAR(1000) NOT NULL,
    trace_id       VARCHAR(64)   NOT NULL,
    event_at       DATETIME(6)   NOT NULL,
    digest         CHAR(64)      NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    created_by     CHAR(36)      NOT NULL,
    updated_by     CHAR(36)      NOT NULL,
    lock_version   BIGINT        NOT NULL DEFAULT 1,
    CONSTRAINT pk_audit_record PRIMARY KEY (id),
    CONSTRAINT ck_audit_digest CHECK (digest REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_audit_resource ON audit_record (resource_type, resource_id, event_at, id);
CREATE INDEX idx_audit_actor ON audit_record (actor_id, event_at, id);

-- draft.base_version_id → content_version 循环引用：建表后补外键
ALTER TABLE draft
    ADD CONSTRAINT fk_draft_base_version FOREIGN KEY (base_version_id)
        REFERENCES content_version (id) ON DELETE RESTRICT;
