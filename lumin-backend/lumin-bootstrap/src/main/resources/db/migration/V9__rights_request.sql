-- =====================================================================
-- V9：数据主体权利请求（docs/04 §5.2 rights_request 原文落地；
-- 03 §2.4 状态机 OPEN→PROCESSING→COMPLETED/HELD（HELD 仅法律保全阻止
-- 匿名化，解除保全后回 PROCESSING）；docs/13 UGC-006；6.9 数据主体权利
-- 自助流程：访问/导出/更正/擦除=匿名化关联。导出有效期 24 小时，下载
-- 二次认证随 UGC-002 后续阶段；result_ref_cipher 经 FieldCipher 加密）。
-- =====================================================================

CREATE TABLE rights_request (
    id                CHAR(36)      NOT NULL,
    user_id           CHAR(36)      NOT NULL,
    kind              VARCHAR(16)   NOT NULL,
    state             VARCHAR(16)   NOT NULL,
    reason            VARCHAR(500)  NOT NULL,
    due_at            DATETIME(6)   NOT NULL,
    hold_id           CHAR(36)      NULL,
    result_ref_cipher VARBINARY(512) NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    created_by        CHAR(36)      NOT NULL,
    updated_by        CHAR(36)      NOT NULL,
    lock_version      BIGINT        NOT NULL DEFAULT 1,
    CONSTRAINT pk_rights_request PRIMARY KEY (id),
    CONSTRAINT fk_rights_request_user FOREIGN KEY (user_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_rights_request_kind CHECK (kind IN ('ACCESS','EXPORT','CORRECT','ERASE')),
    CONSTRAINT ck_rights_request_state CHECK (state IN ('OPEN','PROCESSING','COMPLETED','HELD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_rights_request_user ON rights_request (user_id, created_at, id);
CREATE INDEX idx_rights_request_state ON rights_request (state, due_at, id);
