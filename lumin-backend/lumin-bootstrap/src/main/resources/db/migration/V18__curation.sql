-- =====================================================================
-- V18：运营精选/规则发现（docs/13 UGC-039 一阶段）：曝光干预显式留痕——
-- 提案（OPERATOR）→ 独立审批（ADMIN）→ ACTIVE（读者曝光）→ RETIRED（撤位）。
-- 曝光原因字典化 + 干预标识随响应显式给出（禁止假自然付费榜：运营位永不
-- 伪装成自然排序）。position_rank 为显式干预排序。分类历史不另建表——
-- content_version.category_id 不可变版本链即历史（不拼接不同修订字段）。
-- =====================================================================

CREATE TABLE curation_item (
    id              CHAR(36)     NOT NULL,
    rule_version    INT          NOT NULL DEFAULT 1,
    work_id         CHAR(36)     NOT NULL,
    exposure_reason VARCHAR(24)  NOT NULL,
    note            VARCHAR(200) NULL,
    state           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    position_rank   INT          NOT NULL DEFAULT 0,
    proposed_by     CHAR(36)     NOT NULL,
    reviewed_by     CHAR(36)     NULL,
    created_at DATETIME(6) NOT NULL,
    created_by CHAR(36)    NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by CHAR(36)    NOT NULL,
    lock_version BIGINT    NOT NULL DEFAULT 1,
    CONSTRAINT pk_curation_item PRIMARY KEY (id),
    CONSTRAINT uq_curation_rule_work UNIQUE (rule_version, work_id),
    CONSTRAINT fk_curation_work FOREIGN KEY (work_id) REFERENCES work (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_curation_state CHECK (state IN
        ('PENDING','ACTIVE','REJECTED','RETIRED')),
    CONSTRAINT ck_curation_reason CHECK (exposure_reason IN
        ('FEATURED','EDITOR_PICK','CATEGORY_SPOTLIGHT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_curation_state ON curation_item (state, position_rank);
