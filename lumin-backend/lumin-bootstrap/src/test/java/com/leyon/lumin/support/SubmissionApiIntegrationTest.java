package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提交主链路集成测试（03 §4.1 / R04；T07/T09/T10 约束面）：202 受理语义、
 * INV-06 同事务（version+workflow+Outbox+audit+draft 态）、同目标单流程、
 * 重复提交冻结、章节原子取号与完结禁新章、正文不可变对象写入（O24）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubmissionApiIntegrationTest extends MySqlIntegrationBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static Path storageDir;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) throws IOException {
        storageDir = Files.createTempDirectory("lumin-storage-test");
        registry.add("lumin.storage.dir", () -> storageDir.toString());
    }

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private String createWork() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/works")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andReturn();
        return extract(result, "id");
    }

    private String createWorkDraft(String workId, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("""
                                {"workId":"%s","title":"%s","synopsis":"简介","categoryId":"%s","tags":[]}
                                """.formatted(workId, title, MySqlFixtures.CATEGORY_LITERATURE)))
                .andExpect(status().isOk()).andReturn();
        return extract(result, "id");
    }

    private String createChapter(String workId, String ifMatch) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/works/" + workId + "/chapters")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", ifMatch)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andReturn();
        return extract(result, "id");
    }

    private String createChapterDraft(String workId, String chapterId, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("""
                                {"workId":"%s","chapterId":"%s","title":"第一章","body":"%s"}
                                """.formatted(workId, chapterId, body)))
                .andExpect(status().isOk()).andReturn();
        return extract(result, "id");
    }

    private String submit(String draftId, String ifMatch, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/drafts/" + draftId + "/submissions")
                        .header("Idempotency-Key", key)
                        .header("If-Match", ifMatch)
                        .contentType("application/json").content("{}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String extract(MvcResult result, String field)
            throws java.io.UnsupportedEncodingException {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = "\"" + field + "\":\"";
        int start = body.indexOf(token) + token.length();
        return body.substring(start, body.indexOf('"', start));
    }

    @Test
    void submitWorkDraftPersistsVersionWorkflowOutboxAuditAtomically() throws Exception {
        String workId = createWork();
        String draftId = createWorkDraft(workId, "提交测试");
        String body = submit(draftId, "\"1\"", uuidKey());
        // 202 受理语义（03 §4.1 第 7 条：不显示「发布成功」）
        assertThat(body).contains("\"workflowId\"").contains("\"versionId\"")
                .contains("\"state\":\"SUBMITTED\"");
        String workflowId = extractField(body, "workflowId");
        String versionId = extractField(body, "versionId");
        // INV-06：同事务五写全部落库
        assertThat(jdbc.queryForObject(
                "SELECT state FROM content_version WHERE id = ?", String.class, versionId))
                .isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject(
                "SELECT material_sha256 IS NOT NULL FROM content_version WHERE id = ?",
                Boolean.class, versionId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM publication_workflow WHERE id = ?", String.class, workflowId))
                .isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? "
                        + "AND event_type = 'CONTENT_SUBMITTED_V1'",
                Integer.class, versionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE action = 'draft.submitted' "
                        + "AND resource_id = ?", Integer.class, draftId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM draft WHERE id = ?", String.class, draftId))
                .isEqualTo("SUBMITTED");
        // material_snapshot 列即规范化快照本体
        assertThat(jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(material_snapshot, '$.targetType')) "
                        + "FROM content_version WHERE id = ?", String.class, versionId))
                .isEqualTo("WORK");
    }

    /** MySQL JSON 列会规范化存储（冒号后加空格、键按字典序重排）：提取前先压缩空白。 */
    private static String extractField(String body, String field) {
        String compact = body.replace(" ", "");
        String token = "\"" + field + "\":\"";
        int start = compact.indexOf(token) + token.length();
        return compact.substring(start, compact.indexOf('"', start));
    }

    @Test
    void resubmitAndSecondOpenWorkflowAreRejected() throws Exception {
        String workId = createWork();
        String draft1 = createWorkDraft(workId, "草稿一");
        submit(draft1, "\"1\"", uuidKey());
        // 已 SUBMITTED 草稿冻结：重复提交 → 409/B2001（03 §2.2）
        mockMvc.perform(post("/api/v1/drafts/" + draft1 + "/submissions")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"2\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"));
        // 同目标第二个未结束流程 → 409/B2001（03 §2.2 / INV-01）
        String draft2 = createWorkDraft(workId, "草稿二");
        mockMvc.perform(post("/api/v1/drafts/" + draft2 + "/submissions")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"));
    }

    @Test
    void submissionIdempotentReplayUsesStored202() throws Exception {
        String workId = createWork();
        String draftId = createWorkDraft(workId, "幂等提交");
        String key = uuidKey();
        String first = submit(draftId, "\"1\"", key);
        // 同键同 If-Match：原样回放 202 响应（T09）
        String second = submit(draftId, "\"1\"", key);
        // 存储响应经 MySQL JSON 规范化（空格/键序变化）：比对语义字段而非原文（T09 响应引用一致）
        assertThat(extractField(second, "workflowId"))
                .isEqualTo(extractField(first, "workflowId"));
        assertThat(extractField(second, "versionId"))
                .isEqualTo(extractField(first, "versionId"));
        assertThat(second).contains(extractField(first, "workflowId"));
    }

    @Test
    void submitWithoutIfMatchIs428() throws Exception {
        String workId = createWork();
        String draftId = createWorkDraft(workId, "缺头");
        mockMvc.perform(post("/api/v1/drafts/" + draftId + "/submissions")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("P1002"));
    }

    @Test
    void chapterSubmissionWritesImmutableObject() throws Exception {
        String workId = createWork();
        String chapterId = createChapter(workId, "\"1\"");
        String draftId = createChapterDraft(workId, chapterId, "第一段\\n第二段");
        String body = submit(draftId, "\"1\"", uuidKey());
        String versionId = extractField(body, "versionId");
        // 不可变对象落盘：content/{versionId}/{sha256}.txt，字节与摘要一致（O24 / 03 §4.1 第 5 条）
        String objectKey = jdbc.queryForObject(
                "SELECT object_key FROM content_version WHERE id = ?", String.class, versionId);
        assertThat(objectKey).startsWith("content/" + versionId + "/");
        Path stored = storageDir.resolve(objectKey);
        assertThat(stored).exists();
        assertThat(new String(Files.readAllBytes(stored), StandardCharsets.UTF_8))
                .isEqualTo("第一段\n第二段");
        String bodySha = jdbc.queryForObject(
                "SELECT body_sha256 FROM content_version WHERE id = ?", String.class, versionId);
        assertThat(objectKey).contains(bodySha);
        Integer byteLength = jdbc.queryForObject(
                "SELECT byte_length FROM content_version WHERE id = ?", Integer.class, versionId);
        assertThat(byteLength).isEqualTo("第一段\n第二段".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void chapterSequenceIsAtomicallyAllocatedAndCompletedWorkBlocks() throws Exception {
        String workId = createWork();
        String chapter1 = createChapter(workId, "\"1\"");
        String chapter2 = createChapter(workId, "\"2\"");
        assertThat(jdbc.queryForObject(
                "SELECT sequence_no FROM chapter WHERE id = ?", Integer.class, chapter1))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT sequence_no FROM chapter WHERE id = ?", Integer.class, chapter2))
                .isEqualTo(2);
        // 03 §2.1：完结作品禁止新增章节（COMPLETED 不在取号白名单）
        jdbc.update("UPDATE work SET business_state = 'COMPLETED' WHERE id = ?", workId);
        mockMvc.perform(post("/api/v1/works/" + workId + "/chapters")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"3\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"));
    }

    @Test
    void staleIfMatchOnChapterCreationIs412() throws Exception {
        String workId = createWork();
        mockMvc.perform(post("/api/v1/works/" + workId + "/chapters")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"9\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("B2002"));
    }

    @Test
    void submissionWritesOutboxPayloadWithAuthorAgeBand() throws Exception {
        String workId = createWork();
        String draftId = createWorkDraft(workId, "载荷检查");
        String body = submit(draftId, "\"1\"", uuidKey());
        String versionId = extractField(body, "versionId");
        String payload = jdbc.queryForObject(
                "SELECT payload FROM outbox_event WHERE aggregate_id = ? "
                        + "AND event_type = 'CONTENT_SUBMITTED_V1'", String.class, versionId);
        // 02 §5.2：ContentSubmitted.v1 携带 authorAgeBand 与 materialSha256（年龄不由 LLM 猜测覆盖）
        assertThat(jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.authorAgeBand')) FROM outbox_event "
                        + "WHERE aggregate_id = ? AND event_type = 'CONTENT_SUBMITTED_V1'",
                String.class, versionId)).isEqualTo("C3");
        assertThat(jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.materialSha256')) FROM outbox_event "
                        + "WHERE aggregate_id = ? AND event_type = 'CONTENT_SUBMITTED_V1'",
                String.class, versionId)).isNotBlank();
    }
}
