-- =====================================================================
-- V13：校正 role_grant.scope_type 约束（docs/04 §5.2；05 §3.2）
-- V8 的 ck_role_grant_scope 仍是 GLOBAL/WORK/CASE，与契约
-- RoleGrantRequest.scopeType=GLOBAL/WORK/QUEUE/CATEGORY 及 V2
-- role_assignment.scope_type 不一致；不改 V8 历史文件，追加本迁移校正。
-- =====================================================================

ALTER TABLE role_grant DROP CHECK ck_role_grant_scope;

ALTER TABLE role_grant
    ADD CONSTRAINT ck_role_grant_scope
    CHECK (scope_type IN ('GLOBAL', 'WORK', 'QUEUE', 'CATEGORY'));
