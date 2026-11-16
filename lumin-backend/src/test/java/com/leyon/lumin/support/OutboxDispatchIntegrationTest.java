package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Outbox 投递与机审建案集成测试（03 §4.3 / §5.1 N1 / 02 §5.2）：
 * 事件投递 → 核验 → 建案（QUEUED）→ 状态机 SUBMITTED→AUTO_REVIEW（AUTO_TASK_CLAIMED）
 * → workflow.case_id 回填；inbox 去重幂等；核验失败案件不建（N1 失败路径骨架口径）。
 * 调度在测试环境关闭（lumin.outbox.enabled=false），直调 dispatcher 保证确定性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutboxDispatchIntegrationTest extends MySqlIntegrationBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.foundation.event.OutboxDispatcher dispatcher;

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private String createWork() throws Exception {
        return com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works")
                                .header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk()), "id");
    }

    private String createWorkDraft(String workId) throws Exception {
        return com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts")
                                .header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"workId\":\"" + workId + "\",\"title\":\"提交测试\","
                                        + "\"synopsis\":\"简介\",\"categoryId\":\""
                                        + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk()), "id");
    }

    private String submit(String draftId, String ifMatch) throws Exception {
        return com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts/" + draftId + "/submissions")
                                .header("Idempotency-Key", uuidKey())
                                .header("If-Match", ifMatch)
                                .contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isAccepted()), "workflowId");
    }

    @Test
    void dispatchedEventCreatesQueuedCaseAndClaimsWorkflow() throws Exception {
        String workId = createWork();
        String draftId = createWorkDraft(workId);
        String workflowId = submit(draftId, "\"1\"");
        // 投递前：无案件，事件待投递，流程 SUBMITTED
        assertThat(caseCount(workflowId)).isZero();
        assertThat(workflowState(workflowId)).isEqualTo("SUBMITTED");
        // 投递：N1 核验 → 建案 → 状态机 AUTO_TASK_CLAIMED
        int delivered = dispatcher.deliverPending();
        assertThat(delivered).isGreaterThanOrEqualTo(1);
        String caseId = jdbc.queryForObject(
                "SELECT case_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        assertThat(caseId).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM review_case WHERE id = ?", String.class, caseId))
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT review_stage FROM review_case WHERE id = ?", Integer.class, caseId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT source FROM review_case WHERE id = ?", String.class, caseId))
                .isEqualTo("SUBMISSION");
        assertThat(workflowState(workflowId)).isEqualTo("AUTO_REVIEW");
        // 事件已投递 + 收据写入（03 §4.3 消费者事务内写收据）
        assertThat(jdbc.queryForObject(
                "SELECT delivered_at IS NOT NULL FROM outbox_event WHERE aggregate_id = ? "
                        + "AND event_type = 'CONTENT_SUBMITTED_V1'",
                Boolean.class, jdbc.queryForObject(
                        "SELECT version_id FROM publication_workflow WHERE id = ?",
                        String.class, workflowId))).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_receipt WHERE consumer_name = "
                        + "'publication-workflow-init'", Integer.class)).isEqualTo(1);
    }

    private int caseCount(String workflowId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_case WHERE workflow_id = ?",
                Integer.class, workflowId);
    }

    private String workflowState(String workflowId) {
        return jdbc.queryForObject(
                "SELECT state FROM publication_workflow WHERE id = ?",
                String.class, workflowId);
    }

    @Test
    void redeliveryIsIdempotentViaInbox() throws Exception {
        String workId = createWork();
        String draftId = createWorkDraft(workId);
        String workflowId = submit(draftId, "\"1\"");
        dispatcher.deliverPending();
        // 重复投递（至少一次语义）：inbox 去重，不重复建案
        dispatcher.deliverPending();
        assertThat(caseCount(workflowId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_receipt WHERE consumer_name = "
                        + "'publication-workflow-init'", Integer.class)).isEqualTo(1);
        assertThat(workflowState(workflowId)).isEqualTo("AUTO_REVIEW");
    }

    @Test
    void inconsistentPayloadCreatesNoCaseAndAuditsAlert() throws Exception {
        String workId = createWork();
        String draftId = createWorkDraft(workId);
        String workflowId = submit(draftId, "\"1\"");
        // 篡改事件载荷摘要 → N1 核验失败 → 案件不建 + 告警审计（骨架口径：流程停留 SUBMITTED）
        jdbc.update("UPDATE outbox_event SET payload = JSON_SET(payload, '$.materialSha256', ?) "
                + "WHERE event_type = 'CONTENT_SUBMITTED_V1'", "f".repeat(64));
        dispatcher.deliverPending();
        assertThat(caseCount(workflowId)).isZero();
        assertThat(workflowState(workflowId)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE action = 'submission.verify_failed'",
                Integer.class)).isEqualTo(1);
        // 事件已消费（不重投），收据在案
        assertThat(jdbc.queryForObject(
                "SELECT delivered_at IS NOT NULL FROM outbox_event WHERE "
                        + "event_type = 'CONTENT_SUBMITTED_V1'", Boolean.class)).isTrue();
    }

    @Test
    void consumerFailureBacksOffWithoutDelivery() throws Exception {
        String workId = createWork();
        String draftId = createWorkDraft(workId);
        submit(draftId, "\"1\"");
        // 让消费者抛异常：JSON_REMOVE 缺失 versionId 字段 → 载荷解析 NPE → 退避不投递（03 §4.3）
        jdbc.update("UPDATE outbox_event SET payload = JSON_REMOVE(payload, '$.versionId') "
                + "WHERE event_type = 'CONTENT_SUBMITTED_V1'");
        dispatcher.deliverPending();
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM outbox_event WHERE event_type = 'CONTENT_SUBMITTED_V1'",
                Integer.class);
        assertThat(attempts).isEqualTo(1);
        Integer nextIn = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), next_attempt_at) "
                        + "FROM outbox_event WHERE event_type = 'CONTENT_SUBMITTED_V1'",
                Integer.class);
        assertThat(nextIn).isBetween(0, 2); // 首次失败退避 1s（±扫描间隙）
        assertThat(jdbc.queryForObject(
                "SELECT delivered_at IS NULL FROM outbox_event WHERE "
                        + "event_type = 'CONTENT_SUBMITTED_V1'", Boolean.class)).isTrue();
    }
}
