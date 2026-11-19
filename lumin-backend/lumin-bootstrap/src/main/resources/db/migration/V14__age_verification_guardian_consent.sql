-- V14：UGC-003 年龄核验与监护同意权威表（docs/04 §3 identity）
-- 说明：V1 明确未含 age_verification / guardian_consent；本迁移补齐两表。
-- 方言遵循 V1 头注：BYTEA → VARBINARY，TIMESTAMPTZ → DATETIME(6)，
-- 部分唯一索引 → STORED 生成列 + 唯一索引模拟，CHECK 白名单，FK RESTRICT。

CREATE TABLE age_verification (
    id                CHAR(36)      NOT NULL,
    user_id           CHAR(36)      NOT NULL,
    provider          VARCHAR(40)   NOT NULL,
    proof_ref_cipher  VARBINARY(1024) NOT NULL,
    proof_digest      CHAR(64)      NOT NULL,
    method            VARCHAR(20)   NOT NULL,
    result            VARCHAR(16)   NOT NULL,
    age_band          VARCHAR(2)    NOT NULL,
    verified_at       DATETIME(6)   NOT NULL,
    expires_at        DATETIME(6)   NOT NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    created_by        CHAR(36)      NOT NULL,
    updated_by        CHAR(36)      NOT NULL,
    lock_version      BIGINT        NOT NULL DEFAULT 1,
    CONSTRAINT pk_age_verification PRIMARY KEY (id),
    CONSTRAINT uq_age_verification_proof UNIQUE (provider, proof_digest),
    CONSTRAINT fk_age_verification_user FOREIGN KEY (user_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_age_verification_method CHECK (method IN ('DOCUMENT','FACE','GUARDIAN')),
    CONSTRAINT ck_age_verification_result CHECK (result IN ('VERIFIED','REJECTED')),
    CONSTRAINT ck_age_verification_band CHECK (age_band IN ('C0','C1','C2','C3','C4')),
    CONSTRAINT ck_age_verification_digest CHECK (proof_digest REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_age_verification_expiry CHECK (expires_at > verified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_age_verification_user
    ON age_verification (user_id, verified_at DESC, id);

CREATE TABLE guardian_consent (
    id                CHAR(36)      NOT NULL,
    guardian_id       CHAR(36)      NOT NULL,
    child_id          CHAR(36)      NOT NULL,
    proof_digest      CHAR(64)      NOT NULL,
    proof_ref_cipher  VARBINARY(1024) NOT NULL,
    scope             VARCHAR(20)   NOT NULL,
    granted_at        DATETIME(6)   NOT NULL,
    revoked_at        DATETIME(6)   NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    created_by        CHAR(36)      NOT NULL,
    updated_by        CHAR(36)      NOT NULL,
    lock_version      BIGINT        NOT NULL DEFAULT 1,
    active_slot       TINYINT GENERATED ALWAYS AS
        (CASE WHEN revoked_at IS NULL THEN 1 ELSE NULL END) STORED,
    CONSTRAINT pk_guardian_consent PRIMARY KEY (id),
    CONSTRAINT uq_guardian_consent_proof UNIQUE (proof_digest),
    CONSTRAINT uq_guardian_consent_active UNIQUE (guardian_id, child_id, scope, active_slot),
    CONSTRAINT fk_guardian_consent_guardian FOREIGN KEY (guardian_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_guardian_consent_child FOREIGN KEY (child_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_guardian_consent_scope CHECK (scope IN ('REGISTRATION','PUBLICATION','PUSH')),
    CONSTRAINT ck_guardian_consent_distinct CHECK (guardian_id <> child_id),
    CONSTRAINT ck_guardian_consent_digest CHECK (proof_digest REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_guardian_consent_guardian
    ON guardian_consent (guardian_id, granted_at DESC, id);
CREATE INDEX idx_guardian_consent_child
    ON guardian_consent (child_id, granted_at DESC, id);
