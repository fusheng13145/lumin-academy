-- =====================================================================
-- V5：阅读进度（docs/04 §5.1 reading_progress / engagement 属主；T29）
-- 版本绑定：进度行记录其所指的 content_version；章节发布新版本后
-- 「版本变化提示而不跨版本乱套位置」（GET 暴露 versionId，UI 比对提示）。
-- 边界：0 ≤ position_basis_points ≤ 10000；read_seconds ≥ 0；
-- 客户端时间超前服务端 > 5 分钟由服务层拒绝（校验在应用层，04 §5.1）。
-- =====================================================================

CREATE TABLE reading_progress (
    id                    CHAR(36)    NOT NULL,
    user_id               CHAR(36)    NOT NULL,
    chapter_id            CHAR(36)    NOT NULL,
    version_id            CHAR(36)    NOT NULL,
    position_basis_points INT         NOT NULL,
    read_seconds          INT         NOT NULL,
    client_updated_at     DATETIME(6) NOT NULL,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    created_by            CHAR(36)    NOT NULL,
    updated_by            CHAR(36)    NOT NULL,
    lock_version          BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT pk_reading_progress PRIMARY KEY (id),
    CONSTRAINT uq_reading_progress_user_chapter UNIQUE (user_id, chapter_id),
    CONSTRAINT fk_progress_user FOREIGN KEY (user_id) REFERENCES user_profile (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_progress_chapter FOREIGN KEY (chapter_id) REFERENCES chapter (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_progress_version FOREIGN KEY (version_id) REFERENCES content_version (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_progress_position CHECK (position_basis_points BETWEEN 0 AND 10000),
    CONSTRAINT ck_progress_seconds CHECK (read_seconds >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
