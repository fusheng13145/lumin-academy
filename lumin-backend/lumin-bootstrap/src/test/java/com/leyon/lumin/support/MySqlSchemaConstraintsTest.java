package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DDL 关键约束验证（docs/04 → MySQL 迁移脚本；T07/T09/T14 约束面）：
 * 在真实 MySQL 上验证生成列模拟的部分唯一索引、CHECK 白名单与 grade 配对兜底。
 */
@SpringBootTest
class MySqlSchemaConstraintsTest extends MySqlIntegrationBase {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    @Test
    void outboxAggregateRevisionMustBeUniquePerEventType() {
        String aggregate = newId();
        insertOutbox(newId(), aggregate, 1);
        // U(aggregate_type,aggregate_id,aggregate_revision,event_type)：重复提交被唯一约束拒绝
        assertThrows(DuplicateKeyException.class, () -> insertOutbox(newId(), aggregate, 1));
        // 同聚合不同 revision 合法（INV-06 事件单调）
        insertOutbox(newId(), aggregate, 2);
    }

    private void insertOutbox(String id, String aggregateId, long revision) {
        jdbc.update("""
                INSERT INTO outbox_event (id, event_id, aggregate_type, aggregate_id,
                    aggregate_revision, event_type, schema_version, payload, occurred_at,
                    attempts, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'content_version', ?, ?, 'CONTENT_SUBMITTED_V1', 1, '{}', ?,
                    0, ?, ?, ?, ?)
                """, id, newId(), aggregateId, revision,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
    }

    @Test
    void sameTargetAllowsOnlyOneOpenWorkflow() {
        insertApprovedVersion(newId(), 1);
        insertApprovedVersion(newId(), 2);
        insertWorkflow(newId(), targetVersionId1, "SUBMITTED", null);
        // 03 §2.2 / INV-01：同目标不得有另一个未结束流程（open_slot 生成列唯一）
        String second = newId();
        assertThrows(DuplicateKeyException.class,
                () -> insertWorkflow(second, targetVersionId2, "SUBMITTED", null));
        // 第一个流程到达终态后：新流程可提交（open_slot 释放）
        jdbc.update("""
                UPDATE publication_workflow SET state = 'APPROVED', updated_at = ?,
                    updated_by = ?, lock_version = lock_version + 1 WHERE id = ?
                """, MySqlFixtures.BUSINESS_TIME, MySqlFixtures.SYSTEM, openWorkflowId);
        insertWorkflow(newId(), targetVersionId2, "SUBMITTED", null);
    }

    @Test
    void suspendedWorkflowRequiresReasonAndOthersRejectIt() {
        insertApprovedVersion(newId(), 1);
        // SUSPENDED 必带原因（CHECK 兜底，与 WorkflowSuspension 一致）
        assertThrows(UncategorizedSQLException.class,
                () -> insertWorkflow(newId(), targetVersionId1, "SUSPENDED", null));
        assertThrows(UncategorizedSQLException.class,
                () -> insertWorkflow(newId(), targetVersionId1, "APPROVED", "MACHINE_TIMEOUT"));
    }

    @Test
    void pointerTargetMustBeUnique() {
        insertApprovedVersion(newId(), 1);
        insertPointer(newId());
        assertThrows(DuplicateKeyException.class, () -> insertPointer(newId()));
    }

    @Test
    void machineReportGradePairingEnforcedByCheck() {
        insertApprovedVersion(newId(), 1);
        String workflowId = newId();
        insertWorkflow(workflowId, targetVersionId1, "HUMAN_REVIEW", null);
        String caseId = insertCase(workflowId);
        // MySQL CHECK 违规（SQLSTATE HY000）→ Spring 翻译为 UncategorizedSQLException
        // PASS 缺 grade → 拒绝（T18）
        assertThrows(UncategorizedSQLException.class,
                () -> insertMachineReport(caseId, "PASS", null));
        // REVISE 带 grade → 拒绝
        assertThrows(UncategorizedSQLException.class,
                () -> insertMachineReport(caseId, "REVISE", "G"));
        // PASS + grade 合法；confidence 越界拒绝
        insertMachineReport(caseId, "PASS", "G");
        assertThrows(UncategorizedSQLException.class,
                () -> jdbc.update("""
                        INSERT INTO machine_report (id, case_id, provider, model_version,
                            material_sha256, grade, policy_version, risk, flags,
                            suggested_decision, confidence, evidence_refs, input_tokens,
                            output_tokens, duration_ms, completed_at, created_at, updated_at,
                            created_by, updated_by)
                        VALUES (?, ?, 'p', 'm', ?, 'G', 1, 'LOW', '[]', 'PASS', 1.1,
                            '[]', 10, 5, 1000, ?, ?, ?, ?, ?)
                        """, newId(), caseId, "a".repeat(64),
                        MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                        MySqlFixtures.BUSINESS_TIME, MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM));
    }

    @Test
    void draftShapeCheckRejectsWorkWithBody() {
        // 04 §4 draft：WORK 必无正文（05 §4.1 CHAPTER 禁 synopsis 由 CHECK 一并兜底）
        assertThrows(UncategorizedSQLException.class, () -> jdbc.update("""
                INSERT INTO draft (id, work_id, target_type, title, synopsis, category_id,
                    tags, body_text, state, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'WORK', '标题', '简介', ?, '[]', '不应存在正文', 'EDITABLE',
                    ?, ?, ?, ?)
                """, newId(), MySqlFixtures.WORK_W, MySqlFixtures.CATEGORY_LITERATURE,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM));
        // chapter 禁 synopsis
        assertThrows(UncategorizedSQLException.class, () -> jdbc.update("""
                INSERT INTO draft (id, work_id, chapter_id, target_type, title, synopsis,
                    tags, body_text, state, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, 'CHAPTER', '第一章', '禁用简介', '[]', '正文', 'EDITABLE',
                    ?, ?, ?, ?)
                """, newId(), MySqlFixtures.WORK_W, MySqlFixtures.CHAPTER_C,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM));
    }

    @Test
    void draftAllowsOnlyOneEditablePerTarget() {
        insertDraft(newId(), "EDITABLE");
        // 同目标第二个 EDITABLE 草稿被生成列唯一约束拒绝
        assertThrows(DuplicateKeyException.class, () -> insertDraft(newId(), "EDITABLE"));
        // 非同一目标的 CHAPTER 草稿不冲突
        insertChapterDraft(newId());
    }

    @Test
    void configDualSlotsAllowOneActiveFullAndOneActiveGray() {
        insertConfig(newId(), "REVIEW_ROUTING", 1, 10000, "ACTIVE");
        // 同 key 第二条 ACTIVE 全量基线被 full_slot 唯一拒绝
        assertThrows(DuplicateKeyException.class,
                () -> insertConfig(newId(), "REVIEW_ROUTING", 2, 10000, "ACTIVE"));
        // 同 key 一条 ACTIVE 灰度（1–9999）合法
        insertConfig(newId(), "REVIEW_ROUTING", 3, 5000, "ACTIVE");
        // 第二条 ACTIVE 灰度被 gray_slot 唯一拒绝
        assertThrows(DuplicateKeyException.class,
                () -> insertConfig(newId(), "REVIEW_ROUTING", 4, 2000, "ACTIVE"));
        // 0 比例不得激活（03 §2.4）
        assertThrows(UncategorizedSQLException.class,
                () -> insertConfig(newId(), "REVIEW_ROUTING", 5, 0, "ACTIVE"));
        // 申请人与审批人不同 CHECK
        assertThrows(UncategorizedSQLException.class,
                () -> insertConfigWithReviewer(newId(), "ACCESS_POLICY", 1, 9999, "IN_REVIEW",
                        MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM));
    }

    @Test
    void idempotencyCompositeKeyConflicts() {
        String subject = MySqlFixtures.AUTHOR_A;
        String key = UUID.randomUUID().toString();
        insertIdempotency(subject, key);
        assertThrows(DuplicateKeyException.class, () -> insertIdempotency(subject, key));
        // 不同 key 合法（T09：不同载荷须换键）
        insertIdempotency(subject, UUID.randomUUID().toString());
    }

    // ------------------------------------------------------------------
    // 夹具辅助：持有跨方法引用的主键
    // ------------------------------------------------------------------

    private String openWorkflowId;
    private String targetVersionId1;
    private String targetVersionId2;

    private void insertApprovedVersion(String id, long revision) {
        jdbc.update("""
                INSERT INTO content_version (id, work_id, target_type, revision_no,
                    title, tags, material_snapshot, material_sha256, state, policy_version,
                    created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'WORK', ?, '标题', '[]', '{}', ?, 'APPROVED', 1, ?, ?, ?, ?)
                """, id, MySqlFixtures.WORK_W, revision, "a".repeat(64),
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
        if (revision == 1) {
            targetVersionId1 = id;
        } else {
            targetVersionId2 = id;
        }
    }

    private void insertWorkflow(String id, String versionId, String state, String reason) {
        jdbc.update("""
                INSERT INTO publication_workflow (id, version_id, target_type, target_id,
                    state, policy_version, suspended_reason, created_at, updated_at,
                    created_by, updated_by)
                VALUES (?, ?, 'WORK', ?, ?, 1, ?, ?, ?, ?, ?)
                """, id, versionId, MySqlFixtures.WORK_W, state, reason,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
        if ("SUBMITTED".equals(state) && openWorkflowId == null) {
            openWorkflowId = id;
        }
    }

    private void insertPointer(String id) {
        jdbc.update("""
                INSERT INTO publication_pointer (id, target_type, target_id, version_id,
                    published_at, publication_revision, created_at, updated_at,
                    created_by, updated_by)
                VALUES (?, 'WORK', ?, ?, ?, 1, ?, ?, ?, ?)
                """, id, MySqlFixtures.WORK_W, targetVersionId1,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
    }

    private String insertCase(String workflowId) {
        String caseId = newId();
        jdbc.update("""
                INSERT INTO review_case (id, version_id, workflow_id, work_id, source,
                    risk, priority, state, queue_id, review_stage, response_due_at,
                    completion_due_at, policy_version, created_at, updated_at,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, 'SUBMISSION', 'LOW', 'P1', 'QUEUED', ?, 1, ?, ?, 1,
                    ?, ?, ?, ?)
                """, caseId, targetVersionId1, workflowId, MySqlFixtures.WORK_W,
                newId(), MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
        return caseId;
    }

    private void insertMachineReport(String caseId, String decision, String grade) {
        jdbc.update("""
                INSERT INTO machine_report (id, case_id, provider, model_version,
                    material_sha256, grade, policy_version, risk, flags,
                    suggested_decision, confidence, evidence_refs, input_tokens,
                    output_tokens, duration_ms, completed_at, created_at, updated_at,
                    created_by, updated_by)
                VALUES (?, ?, 'provider-x', 'model-x', ?, ?, 1, 'LOW', '[]', ?, 0.99,
                    '["ev:1"]', 10, 5, 1000, ?, ?, ?, ?, ?)
                """, newId(), caseId, "a".repeat(64), grade, decision,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
    }

    private void insertDraft(String id, String state) {
        jdbc.update("""
                INSERT INTO draft (id, work_id, target_type, title, synopsis, category_id,
                    tags, state, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'WORK', '标题', '简介', ?, '[]', ?, ?, ?, ?, ?)
                """, id, MySqlFixtures.WORK_W, MySqlFixtures.CATEGORY_LITERATURE, state,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
    }

    private void insertChapterDraft(String id) {
        jdbc.update("""
                INSERT INTO draft (id, work_id, chapter_id, target_type, title, tags,
                    body_text, state, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, 'CHAPTER', '第一章', '[]', '正文', 'EDITABLE', ?, ?, ?, ?)
                """, id, MySqlFixtures.WORK_W, MySqlFixtures.CHAPTER_C,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
    }

    private void insertConfig(String id, String key, long revision, int rollout, String state) {
        insertConfigWithReviewer(id, key, revision, rollout, state, MySqlFixtures.SYSTEM, null);
    }

    private void insertConfigWithReviewer(String id, String key, long revision, int rollout,
                                          String state, String requestedBy, String approvedBy) {
        jdbc.update("""
                INSERT INTO config_revision (id, config_key, revision, base_revision,
                    schema_version, payload, state, reason, requested_by, approved_by,
                    effective_at, rollout_basis_points, created_at, updated_at,
                    created_by, updated_by)
                VALUES (?, ?, ?, 0, 1, '{}', ?, '测试', ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, key, revision, state, requestedBy, approvedBy,
                MySqlFixtures.BUSINESS_TIME, rollout, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
    }

    private void insertIdempotency(String subject, String key) {
        jdbc.update("""
                INSERT INTO idempotency_record (subject_id, method, path, `key`,
                    request_digest, response_code, response_body, expires_at)
                VALUES (?, 'POST', '/api/v1/drafts', ?, ?, 200, '{}', ?)
                """, subject, key, "b".repeat(64), MySqlFixtures.BUSINESS_TIME);
    }
}
