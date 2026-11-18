-- =====================================================================
-- V10：素材上传与正式资源（docs/04 §4 catalog；docs/13 UGC-010 一阶段；
-- contracts/p0.openapi.yaml asset-uploads 切片）：隔离上传会话 + 净化后
-- 正式资源两表（上传会话 ID ≠ 正式资源 ID）。状态机
-- AWAITING_UPLOAD → CHECKING → COMPLETE/REJECTED（EXPIRED 随签名过期）。
-- 仅 JPEG/PNG ≤5MiB；像素上限 2500 万；净化去 EXIF 后正式 sha256 与
-- 原始摘要分开；COVER 仅本作品可引用、EVIDENCE 绑定案件不得改绑。
-- =====================================================================

CREATE TABLE asset_upload (
    id            CHAR(36)     NOT NULL,
    purpose       VARCHAR(16)  NOT NULL,
    work_id       CHAR(36)     NULL,
    case_id       CHAR(36)     NULL,
    content_type  VARCHAR(24)  NOT NULL,
    size_bytes    INT          NOT NULL,
    sha256_raw    CHAR(64)     NOT NULL,
    object_key    VARCHAR(300) NOT NULL,
    state         VARCHAR(16)  NOT NULL,
    asset_id      CHAR(36)     NULL,
    failure_reason VARCHAR(40) NULL,
    requested_by  CHAR(36)     NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    created_by    CHAR(36)     NOT NULL,
    updated_by    CHAR(36)     NOT NULL,
    lock_version  BIGINT       NOT NULL DEFAULT 1,
    CONSTRAINT pk_asset_upload PRIMARY KEY (id),
    CONSTRAINT ck_asset_upload_purpose CHECK (purpose IN ('COVER','EVIDENCE')),
    CONSTRAINT ck_asset_upload_ct CHECK (content_type IN ('image/jpeg','image/png')),
    CONSTRAINT ck_asset_upload_size CHECK (size_bytes BETWEEN 1 AND 5242880),
    CONSTRAINT ck_asset_upload_state CHECK (state IN
        ('AWAITING_UPLOAD','CHECKING','COMPLETE','REJECTED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_asset_upload_state ON asset_upload (state, expires_at);

CREATE TABLE content_asset (
    id           CHAR(36)     NOT NULL,
    purpose      VARCHAR(16)  NOT NULL,
    work_id      CHAR(36)     NULL,
    case_id      CHAR(36)     NULL,
    content_type VARCHAR(24)  NOT NULL,
    size_bytes   INT          NOT NULL,
    sha256       CHAR(64)     NOT NULL,
    object_key   VARCHAR(300) NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    created_by   CHAR(36)     NOT NULL,
    updated_by   CHAR(36)     NOT NULL,
    lock_version BIGINT       NOT NULL DEFAULT 1,
    CONSTRAINT pk_content_asset PRIMARY KEY (id),
    CONSTRAINT ck_content_asset_purpose CHECK (purpose IN ('COVER','EVIDENCE')),
    CONSTRAINT ck_content_asset_ct CHECK (content_type IN ('image/jpeg','image/png')),
    CONSTRAINT ck_content_asset_sha CHECK (sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX idx_content_asset_work ON content_asset (work_id, purpose);
