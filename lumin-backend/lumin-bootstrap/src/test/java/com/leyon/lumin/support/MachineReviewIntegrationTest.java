package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 机审报告接收与 N4 路由集成测试（03 §5.1 N3–N5 / 05 §4.3 / T16–T18 约束面）：
 * AUTO_PASS 发布事务（APPROVED + 指针 + ContentPublished）、AUTO_REVISE 驳回、
 * 章节父作品条件、HUMAN 路由、绑定核验拒绝、晚到报告 409。
 * 校准通过（配置开启）+ 抽检闸恒未命中（嵌套配置），确定性验证 AUTO 链路。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class MachineReviewIntegrationTest extends MySqlIntegrationBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static Path storageDir;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) throws IOException {
        storageDir = Files.createTempDirectory("lumin-storage-mr");
        registry.add("lumin.storage.dir", () -> storageDir.toString());
    }

    /** 嵌套配置：抽检闸恒未命中（验证 AUTO 链路；抽检命中转人工由单测覆盖采样函数）。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class NeverSampledGate {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.leyon.lumin.moderation.service.AutoSampleGate neverSampled() {
            return caseId -> false;
        }
    }

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    /** 提交并投递：返回 (workflowId, versionId, caseId, materialSha256)。 */
    private Submitted submittedWork() throws Exception {
        String workId = create("/api/v1/works", "{}");
        String draftId = create("/api/v1/drafts", """
                {"workId":"%s","title":"机审测试","synopsis":"简介","categoryId":"%s","tags":[]}
                """.formatted(workId, MySqlFixtures.CATEGORY_LITERATURE));
        String workflowId = submit(draftId, "\"1\"");
        dispatcher.deliverPending();
        String caseId = jdbc.queryForObject(
                "SELECT case_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String versionId = jdbc.queryForObject(
                "SELECT version_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?", String.class, versionId);
        return new Submitted(workId, draftId, workflowId, caseId, versionId, materialSha);
    }

    private record Submitted(String workId, String draftId, String workflowId, String caseId,
                             String versionId, String materialSha) {
    }

    private ResultActions sendReport(Submitted s, String risk, String decision, String grade,
                                     String confidence) throws Exception {
        String body = """
                {"caseId":"%s","workflowId":"%s","versionId":"%s","policyVersion":1,
                 "materialSha256":"%s","modelVersion":"guard-v1","risk":"%s","riskFlags":[],
                 "suggestedDecision":"%s",%s"confidence":%s,"evidenceRefs":["ev:1"],
                 "inputTokens":100,"outputTokens":10,"durationMs":1000}
                """.formatted(s.caseId(), s.workflowId(), s.versionId(), s.materialSha(),
                risk, decision,
                grade == null ? "" : "\"grade\":\"" + grade + "\",",
                confidence);
        return mockMvc.perform(post("/internal/v1/machine-reports")
                .contentType("application/json")
                .content(java.text.Normalizer.normalize(body, java.text.Normalizer.Form.NFKC)
                        .replace("\n", "")));
    }

    @Autowired
    private com.leyon.lumin.foundation.event.OutboxDispatcher dispatcher;

    @Test
    void autoAcceptPublishesPointerAndContentPublished() throws Exception {
        Submitted s = submittedWork();
        sendReport(s, "LOW", "PASS", "G", "0.99")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.resourceType").value("machine_report"));
        dispatcher.deliverPending();
        // N5：SYSTEM 裁决记录 + case DECIDED
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_decision WHERE case_id = ? AND actor_type = 'SYSTEM' "
                        + "AND decision = 'PASS' AND grade = 'G'", Integer.class, s.caseId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM review_case WHERE id = ?", String.class, s.caseId()))
                .isEqualTo("DECIDED");
        // 发布事务：版本/流程 APPROVED + 公开指针 + ContentPublished.v1
        assertThat(jdbc.queryForObject(
                "SELECT state FROM content_version WHERE id = ?", String.class, s.versionId()))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM publication_workflow WHERE id = ?", String.class, s.workflowId()))
                .isEqualTo("APPROVED");
        String pointerVersion = jdbc.queryForObject(
                "SELECT version_id FROM publication_pointer WHERE target_type = 'WORK' "
                        + "AND target_id = ?", String.class, s.workId());
        assertThat(pointerVersion).isEqualTo(s.versionId());
        Integer published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'CONTENT_PUBLISHED_V1' "
                        + "AND aggregate_id = ?", Integer.class, s.versionId());
        assertThat(published).isEqualTo(1);
        // WORK 首发非新章节（03 §6.6：追更仅由新章节正式发布触发）
        assertThat(jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.isNewChapter')) FROM outbox_event "
                        + "WHERE event_type = 'CONTENT_PUBLISHED_V1' AND aggregate_id = ?",
                String.class, s.versionId())).isEqualTo("false");
    }

    @Test
    void autoReviseRejectsVersionAndWorkflow() throws Exception {
        Submitted s = submittedWork();
        // REVISE 禁止 grade（T18）
        sendReport(s, "LOW", "REVISE", "G", "0.99")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
        sendReport(s, "LOW", "REVISE", null, "0.99")
                .andExpect(status().isOk());
        dispatcher.deliverPending();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM content_version WHERE id = ?", String.class, s.versionId()))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM publication_workflow WHERE id = ?", String.class, s.workflowId()))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM publication_pointer WHERE target_id = ?",
                Integer.class, s.workId())).isZero();
    }

    @Test
    void humanRoutedReportKeepsCaseQueuedWithSlaUpdate() throws Exception {
        Submitted s = submittedWork();
        // MEDIUM 风险 → 必人审（T16）；SLA 更新 P1
        sendReport(s, "MEDIUM", "PASS", "G", "0.99")
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT state FROM review_case WHERE id = ?", String.class, s.caseId()))
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "SELECT risk FROM review_case WHERE id = ?", String.class, s.caseId()))
                .isEqualTo("MEDIUM");
        assertThat(jdbc.queryForObject(
                "SELECT priority FROM review_case WHERE id = ?", String.class, s.caseId()))
                .isEqualTo("P1");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM publication_workflow WHERE id = ?", String.class, s.workflowId()))
                .isEqualTo("HUMAN_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_decision WHERE case_id = ?",
                Integer.class, s.caseId())).isZero();
    }

    @Test
    void chapterWithoutPublishedWorkStaysHumanReview() throws Exception {
        String workId = create("/api/v1/works", "{}");
        String chapterId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works/" + workId + "/chapters")
                                .header("Idempotency-Key", uuidKey())
                                .header("If-Match", "\"1\"")
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = create("/api/v1/drafts", "{\"workId\":\"" + workId
                + "\",\"chapterId\":\"" + chapterId + "\",\"title\":\"第一章\",\"body\":\"第一段\\n第二段\"}");
        String workflowId = submit(draftId, "\"1\"");
        dispatcher.deliverPending();
        String caseId = jdbc.queryForObject(
                "SELECT case_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String versionId = jdbc.queryForObject(
                "SELECT version_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?", String.class, versionId);
        Submitted s = new Submitted(workId, draftId, workflowId, caseId, versionId, materialSha);
        // 03 §2.3：父作品未发布 → 停留 HUMAN_REVIEW，不将不满足前提的审批提交成功
        sendReport(s, "LOW", "PASS", "G", "0.99").andExpect(status().isOk());
        dispatcher.deliverPending();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM publication_workflow WHERE id = ?", String.class, s.workflowId()))
                .isEqualTo("HUMAN_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM publication_pointer WHERE target_type = 'CHAPTER' "
                        + "AND target_id = ?", Integer.class, chapterId)).isZero();
    }

    @Test
    void bindingMismatchAndStaleReportAreRejected() throws Exception {
        Submitted s = submittedWork();
        // 错 materialSha256 → 400/P1001（T18），无报告行
        mockMvc.perform(post("/internal/v1/machine-reports")
                        .contentType("application/json")
                        .content("""
                                {"caseId":"%s","workflowId":"%s","versionId":"%s","policyVersion":1,
                                 "materialSha256":"%s","modelVersion":"m","risk":"LOW","riskFlags":[],
                                 "suggestedDecision":"PASS","grade":"G","confidence":0.99,
                                 "evidenceRefs":["ev:1"],"inputTokens":1,"outputTokens":1,
                                 "durationMs":1}
                                """.formatted(s.caseId(), s.workflowId(), s.versionId(),
                                "e".repeat(64))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
        sendReport(s, "LOW", "PASS", "G", "0.99").andExpect(status().isOk());
        dispatcher.deliverPending();
        // 晚到报告：案件已 DECIDED → 409/B2001 + 过期审计
        sendReport(s, "LOW", "PASS", "G", "0.99")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE action = 'machine_report.stale'",
                Integer.class)).isEqualTo(1);
    }

    private String create(String path, String body) throws Exception {
        return com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post(path).header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content(body))
                .andExpect(status().isOk()), "id");
    }

    private String submit(String draftId, String ifMatch) throws Exception {
        return com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts/" + draftId + "/submissions")
                                .header("Idempotency-Key", uuidKey())
                                .header("If-Match", ifMatch)
                                .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted()), "workflowId");
    }
}
