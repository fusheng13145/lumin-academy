-- =====================================================================
-- V12：提案与审批追溯（docs/04 §5.2 proposal_review；docs/13 UGC-004）
-- 契约 RoleGrantView.reviews 要求完整、不可覆写的审批记录；V8 只在
-- role_grant 行上保留最后一个 reviewed_by/reviewed_at，无法表达多轮/历史。
-- 本表按 resource_type/resource_id 归属各提案域，追加写入，审批事务内与
-- 业务状态同提交；审计事实不删。角色授予使用 resource_type=ROLE_GRANT。
-- 同时回填既有已审批行，避免历史授予详情出现空 reviews。
-- =====================================================================

CREATE TABLE proposal_review (
    id                CHAR(36)      NOT NULL,
    resource_type     VARCHAR(24)   NOT NULL,
    resource_id       CHAR(36)      NOT NULL,
    actor_id          CHAR(36)      NOT NULL,
    action            VARCHAR(24)   NOT NULL,
    reason            VARCHAR(1000) NOT NULL,
    evidence_refs     JSON          NOT NULL,
    reviewed_revision BIGINT        NOT NULL,
    event_at          DATETIME(6)   NOT NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    created_by        CHAR(36)      NOT NULL,
    updated_by        CHAR(36)      NOT NULL,
    lock_version      BIGINT        NOT NULL DEFAULT 1,
    CONSTRAINT pk_proposal_review PRIMARY KEY (id),
    CONSTRAINT fk_proposal_review_actor FOREIGN KEY (actor_id)
        REFERENCES user_profile (id) ON DELETE RESTRICT,
    CONSTRAINT ck_proposal_review_resource_type CHECK (resource_type IN
        ('CONSENT', 'ROLE_GRANT', 'RESTRICTION', 'APPEAL', 'RIGHTS_REQUEST', 'CONFIG')),
    CONSTRAINT ck_proposal_review_action CHECK (action IN
        ('APPROVE', 'REJECT', 'VERIFY', 'REVOKE', 'UPHOLD', 'OVERTURN',
         'PROCESS', 'COMPLETE', 'HOLD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_proposal_review_resource
    ON proposal_review (resource_type, resource_id, event_at, id);

-- 回填 V8 既有审批行：一个角色授予只保留最后审批人，回填为单条记录；
-- reviewed_revision 取审批前版本（当前 lock_version - 1）。
INSERT INTO proposal_review (id, resource_type, resource_id, actor_id, action,
    reason, evidence_refs, reviewed_revision, event_at, created_at, updated_at,
    created_by, updated_by, lock_version)
SELECT UUID(), 'ROLE_GRANT', g.id, g.reviewed_by,
    CASE g.state WHEN 'APPROVED' THEN 'APPROVE' ELSE 'REJECT' END,
    CONCAT('历史审批回填：', COALESCE(g.reason, '无原始理由')),
    JSON_ARRAY(CONCAT('role_grant:', g.id)),
    GREATEST(g.lock_version - 1, 1),
    g.reviewed_at, g.reviewed_at, g.reviewed_at, g.reviewed_by, g.reviewed_by, 1
FROM role_grant g
WHERE g.reviewed_by IS NOT NULL AND g.reviewed_at IS NOT NULL;
