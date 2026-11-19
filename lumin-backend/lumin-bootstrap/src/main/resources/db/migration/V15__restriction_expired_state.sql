-- =====================================================================
-- V15：restriction 状态机补 EXPIRED（03 §2.4）：
-- 临时措施到期由 SYSTEM 扫描持久化为 EXPIRED，进入复核告警；
-- 到期只退出 ACTIVE 生效集合，不自动恢复公开（改判恢复仍需人工复核）。
-- =====================================================================

ALTER TABLE restriction DROP CHECK ck_restriction_state;
ALTER TABLE restriction ADD CONSTRAINT ck_restriction_state
    CHECK (state IN ('PENDING','ACTIVE','REVOKED','REJECTED','EXPIRED'));
