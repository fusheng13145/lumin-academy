-- =====================================================================
-- 启明书院 · AI 创作模块（V7）
-- 依据：docs/15 §8 长期记忆、docs/17 §11 AI 数据模型
-- 设计原则：运行/阶段/候选追加式存档；记忆事实先 CANDIDATE，作者确认后 ACTIVE；
--           AI 生成正文永不直接写 chapter/draft，必须经 ai_candidate → 作者采纳。
-- =====================================================================

CREATE TABLE ai_run (
    id                 CHAR(36)      NOT NULL,
    work_id            CHAR(36)      NOT NULL,
    chapter_id         CHAR(36)      NULL,
    mode               VARCHAR(20)   NOT NULL,
    state              VARCHAR(24)   NOT NULL,
    instruction        TEXT          NULL,
    provider           VARCHAR(32)   NOT NULL,
    model              VARCHAR(128)  NOT NULL,
    memory_revision    VARCHAR(64)   NULL,
    error_message      TEXT          NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    CONSTRAINT pk_ai_run PRIMARY KEY (id),
    CONSTRAINT ck_ai_run_mode CHECK (mode IN ('ASSISTED','AUTONOMOUS')),
    CONSTRAINT ck_ai_run_state CHECK (state IN ('QUEUED','RUNNING','WAITING_AUTHOR','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT fk_ai_run_work FOREIGN KEY (work_id) REFERENCES work (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_ai_run_work_created ON ai_run (work_id, created_at DESC);
CREATE INDEX idx_ai_run_state ON ai_run (state, updated_at DESC);

CREATE TABLE ai_run_stage (
    id                 CHAR(36)      NOT NULL,
    run_id             CHAR(36)      NOT NULL,
    stage_type         VARCHAR(32)   NOT NULL,
    attempt            INT           NOT NULL,
    state              VARCHAR(16)   NOT NULL,
    output             MEDIUMTEXT    NULL,
    error_message      TEXT          NULL,
    started_at         DATETIME(6)   NOT NULL,
    finished_at        DATETIME(6)   NULL,
    CONSTRAINT pk_ai_run_stage PRIMARY KEY (id),
    CONSTRAINT ck_ai_stage_type CHECK (stage_type IN ('MEMORY_CONTEXT','PLAN','DRAFT','CONSISTENCY','REVISION','MEMORY_EXTRACT','CANDIDATE')),
    CONSTRAINT ck_ai_stage_state CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','SKIPPED')),
    CONSTRAINT fk_ai_stage_run FOREIGN KEY (run_id) REFERENCES ai_run (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_ai_stage_run_started ON ai_run_stage (run_id, started_at);

CREATE TABLE ai_candidate (
    id                 CHAR(36)      NOT NULL,
    run_id             CHAR(36)      NOT NULL,
    content            MEDIUMTEXT    NOT NULL,
    source             VARCHAR(48)   NOT NULL,
    state              VARCHAR(16)   NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    CONSTRAINT pk_ai_candidate PRIMARY KEY (id),
    CONSTRAINT ck_ai_candidate_state CHECK (state IN ('PENDING','ACCEPTED','REJECTED')),
    CONSTRAINT fk_ai_candidate_run FOREIGN KEY (run_id) REFERENCES ai_run (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_ai_candidate_run ON ai_candidate (run_id, created_at);

CREATE TABLE memory_fact (
    id                 CHAR(36)      NOT NULL,
    work_id            CHAR(36)      NOT NULL,
    kind               VARCHAR(16)   NOT NULL,
    subject_key        VARCHAR(120)  NOT NULL,
    content            TEXT          NOT NULL,
    status             VARCHAR(16)   NOT NULL,
    confidence         DECIMAL(5,4)  NOT NULL DEFAULT 0.7000,
    source_version     VARCHAR(64)   NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    deleted_at         DATETIME(6)   NULL,
    CONSTRAINT pk_memory_fact PRIMARY KEY (id),
    CONSTRAINT ck_memory_kind CHECK (kind IN ('WORLD','CHARACTER','PLOT','FORESHADOW','STYLE','CONSTRAINT')),
    CONSTRAINT ck_memory_status CHECK (status IN ('CANDIDATE','ACTIVE','ARCHIVED','REJECTED')),
    CONSTRAINT ck_memory_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT fk_memory_work FOREIGN KEY (work_id) REFERENCES work (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_memory_work_status ON memory_fact (work_id, status, updated_at DESC);
CREATE INDEX idx_memory_subject ON memory_fact (work_id, subject_key);

CREATE TABLE memory_event (
    id                 CHAR(36)      NOT NULL,
    work_id            CHAR(36)      NOT NULL,
    fact_id            CHAR(36)      NOT NULL,
    event_type         VARCHAR(24)   NOT NULL,
    before_json        TEXT          NULL,
    after_json         TEXT          NULL,
    actor_id           CHAR(36)      NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    CONSTRAINT pk_memory_event PRIMARY KEY (id),
    CONSTRAINT ck_memory_event_type CHECK (event_type IN ('EXTRACTED','CONFIRMED','REJECTED','ARCHIVED','EDITED')),
    CONSTRAINT fk_memory_event_fact FOREIGN KEY (fact_id) REFERENCES memory_fact (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_memory_event_work ON memory_event (work_id, created_at DESC);
