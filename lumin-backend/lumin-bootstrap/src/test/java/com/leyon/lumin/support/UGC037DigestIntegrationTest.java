package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-037 新章聚合通知集成测试（docs/13 M1；07 T34 接线；04 §6.1）：
 * 发布新章 → 订阅者收 1 条站内 UPDATE 通知 + digest 落表（每作品每接收者
 * 每自然日最多 1 条——同日第 2 章去重）；修订（章节重发布 isNewChapter=false）
 * 不触发；退订不推；免打扰时段不推；作品下架不推（每接收者
 * VisibilityPolicy 裁决经 ChapterAccessPort——父作品 REMOVED → BLOCKED）；
 * inbox 收据幂等（同事件重投不重复）。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC037DigestIntegrationTest extends MySqlIntegrationBase {

    /** 嵌套配置：抽检闸恒未命中——AUTO 链路确定性验证（抽检函数另有单测）。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class NeverSampledGate {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.leyon.lumin.moderation.service.AutoSampleGate neverSampled() {
            return caseId -> false;
        }
    }

    private static final String AUTHOR = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.AUTHOR;
    private static final String READER_C3 = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3;
    private static final String READER_C1 = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C1;
    private static final String READER_C4 = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C4;
    private static final String SUBJECT_C3 =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3_SUBJECT;
    private static final String SUBJECT_C1 =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C1_SUBJECT;
    private static final String SUBJECT_C4 =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C4_SUBJECT;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.foundation.event.OutboxDispatcher dispatcher;
    @Autowired
    private com.leyon.lumin.engagement.event.ChapterDigestConsumer digestConsumer;

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
        MySqlFixtures.insertReaderProfiles(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(org.springframework.test.web.servlet.ResultActions actions,
                                String name) throws java.io.UnsupportedEncodingException {
        return com.leyon.lumin.support.TestResponseSupport.field(actions, name);
    }

    /** 发布一部 G 级作品（首版本经机审 AUTO PASS）。 */
    private String publishWork(String title) throws Exception {
        String workId = field(mockMvc.perform(post("/api/v1/works")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = field(mockMvc.perform(post("/api/v1/drafts")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"title\":\"" + title + "\","
                                + "\"synopsis\":\"简介\",\"categoryId\":\""
                                + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk()), "id");
        submitAndAutoPass(draftId);
        return workId;
    }

    /** 在已发布作品下新增章节并发布（新章首指针 → isNewChapter=true）。 */
    private String publishNewChapter(String workId, String title) throws Exception {
        return publishNewChapter(workId, title, true);
    }

    /**
     * 新章发布；dispatchFinal=false 时暂停末轮投递——CONTENT_PUBLISHED_V1
     * 已追加未消费，供在消费前注入作品态（下架分支）。
     */
    private String publishNewChapter(String workId, String title,
                                     boolean dispatchFinal) throws Exception {
        long lock = jdbc.queryForObject(
                "SELECT lock_version FROM work WHERE id = ?", Long.class, workId);
        String chapterId = field(mockMvc.perform(
                        post("/api/v1/works/" + workId + "/chapters")
                                .header("X-Lumin-Demo-Subject", AUTHOR)
                                .header("Idempotency-Key", uuidKey())
                                .header("If-Match", "\"" + lock + "\"")
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        publishChapterVersion(workId, chapterId, title, "G", dispatchFinal);
        return chapterId;
    }

    /** 为既有章节发布新版本（指针交换 → isNewChapter=false，修订语义）。 */
    private void publishChapterVersion(String workId, String chapterId, String title,
                                       String grade, boolean dispatchFinal) throws Exception {
        String draftId = field(mockMvc.perform(post("/api/v1/drafts")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"chapterId\":\"" + chapterId
                                + "\",\"title\":\"" + title + "\",\"body\":\"正文内容\"}"))
                .andExpect(status().isOk()), "id");
        submitAndAutoPassGrade(draftId, grade, dispatchFinal);
    }

    /** 提交草稿 + 机审 PASS + 投递（发布事务完成）。 */
    private void submitAndAutoPass(String draftId) throws Exception {
        submitAndAutoPassGrade(draftId, "G", true);
    }

    private void submitAndAutoPassGrade(String draftId, String grade,
                                        boolean dispatchFinal) throws Exception {
        mockMvc.perform(post("/api/v1/drafts/" + draftId + "/submissions")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted());
        dispatcher.deliverPending();
        String workflowId = jdbc.queryForObject(
                "SELECT id FROM publication_workflow WHERE version_id = (SELECT id FROM "
                        + "content_version WHERE source_draft_id = ?)", String.class, draftId);
        String caseId = jdbc.queryForObject(
                "SELECT case_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String versionId = jdbc.queryForObject(
                "SELECT version_id FROM publication_workflow WHERE id = ?", String.class,
                workflowId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?", String.class,
                versionId);
        mockMvc.perform(post("/internal/v1/machine-reports")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"caseId\":\"" + caseId + "\",\"workflowId\":\"" + workflowId
                                + "\",\"versionId\":\"" + versionId + "\",\"policyVersion\":1,"
                                + "\"materialSha256\":\"" + materialSha
                                + "\",\"modelVersion\":\"guard-v1\",\"risk\":\"LOW\",\"riskFlags\":[],"
                                + "\"suggestedDecision\":\"PASS\",\"grade\":\"" + grade
                                + "\",\"confidence\":0.99,"
                                + "\"evidenceRefs\":[\"ev:1\"],\"inputTokens\":100,\"outputTokens\":10,"
                                + "\"durationMs\":1000}"))
                .andExpect(status().isOk());
        dispatcher.deliverPending();
        // CONTENT_PUBLISHED_V1 在裁决投递轮内追加，需要再一轮才被追更消费者消费
        if (dispatchFinal) {
            dispatcher.deliverPending();
        }
    }

    private void subscribe(String subject, String workId, String quietStart, String quietEnd,
                           String timezoneId, boolean pushEnabled) throws Exception {
        mockMvc.perform(post("/api/v1/me/subscriptions")
                        .header("X-Lumin-Demo-Subject", subject)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/me/subscriptions/" + workId + "/preferences")
                        .header("X-Lumin-Demo-Subject", subject)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"quietStart\":\"" + quietStart + "\",\"quietEnd\":\""
                                + quietEnd + "\",\"timezoneId\":\"" + timezoneId
                                + "\",\"pushEnabled\":" + pushEnabled + "}"))
                .andExpect(status().isOk());
    }

    private int updateNotificationCount(String recipient) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE recipient_id = ? AND category = 'UPDATE'",
                Integer.class, recipient);
    }

    private int digestCount(String recipient, String workId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_digest WHERE recipient_id = ? AND work_id = ?",
                Integer.class, recipient, workId);
    }

    @Test
    void newChapterSendsSingleUpdateToSubscriber() throws Exception {
        String workId = publishWork("追更聚合作品");
        subscribe(READER_C3, workId, "22:00", "08:00", "UTC", false);
        String chapterId = publishNewChapter(workId, "第二章");

        assertThat(updateNotificationCount(SUBJECT_C3)).isEqualTo(1);
        assertThat(digestCount(SUBJECT_C3, workId)).isEqualTo(1);
        String latestChapter = jdbc.queryForObject(
                "SELECT latest_chapter_id FROM notification_digest WHERE recipient_id = ?",
                String.class, SUBJECT_C3);
        assertThat(latestChapter).isEqualTo(chapterId);
        String title = jdbc.queryForObject(
                "SELECT title FROM notification WHERE recipient_id = ? AND category = 'UPDATE'",
                String.class, SUBJECT_C3);
        assertThat(title).isEqualTo("追更更新");
        // 未订阅读者不收
        assertThat(updateNotificationCount(SUBJECT_C1)).isEqualTo(0);
    }

    @Test
    void secondChapterSameDayIsDeduped() throws Exception {
        String workId = publishWork("同日去重作品");
        subscribe(READER_C3, workId, "22:00", "08:00", "UTC", false);
        publishNewChapter(workId, "第二章");
        publishNewChapter(workId, "第三章");

        assertThat(updateNotificationCount(SUBJECT_C3)).isEqualTo(1);
        assertThat(digestCount(SUBJECT_C3, workId)).isEqualTo(1);
    }

    @Test
    void chapterRevisionDoesNotTriggerDigest() throws Exception {
        String workId = publishWork("修订不触发作品");
        subscribe(READER_C3, workId, "22:00", "08:00", "UTC", false);
        String chapterId = publishNewChapter(workId, "第二章");
        assertThat(updateNotificationCount(SUBJECT_C3)).isEqualTo(1);

        // 同章节再发一版（指针交换 → isNewChapter=false）→ 不新增
        publishChapterVersion(workId, chapterId, "第二章（修订）", "G", true);
        assertThat(updateNotificationCount(SUBJECT_C3)).isEqualTo(1);
        assertThat(digestCount(SUBJECT_C3, workId)).isEqualTo(1);
    }

    @Test
    void unsubscribeStopsDigest() throws Exception {
        String workId = publishWork("退订停止作品");
        subscribe(READER_C3, workId, "22:00", "08:00", "UTC", false);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/me/subscriptions/" + workId)
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        publishNewChapter(workId, "第二章");
        assertThat(updateNotificationCount(SUBJECT_C3)).isEqualTo(0);
        assertThat(digestCount(SUBJECT_C3, workId)).isEqualTo(0);
    }

    @Test
    void quietHoursWindowDropsDigest() throws Exception {
        String workId = publishWork("免打扰作品");
        // 免打扰窗口覆盖当前 UTC 时刻（±10 分钟；跨午夜由策略环绕语义处理）
        LocalTime now = LocalTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        subscribe(READER_C4, workId, now.minusMinutes(10).toString(),
                now.plusMinutes(10).toString(), "UTC", false);

        publishNewChapter(workId, "第二章");
        assertThat(updateNotificationCount(SUBJECT_C4)).isEqualTo(0);
        assertThat(digestCount(SUBJECT_C4, workId)).isEqualTo(0);
    }

    @Test
    void workRemovedStopsDigest() throws Exception {
        String workId = publishWork("下架停止作品");
        subscribe(READER_C3, workId, "22:00", "08:00", "UTC", false);
        // 发布新章但暂停末轮投递（CONTENT_PUBLISHED_V1 已追加未消费）
        publishNewChapter(workId, "第二章", false);
        // 下架事实注入（治理下架全流程另有 T23 测试）——消费端经
        // ChapterAccessPort/VisibilityPolicy 步骤 2：父作品 REMOVED → BLOCKED
        jdbc.update("UPDATE work SET business_state = 'REMOVED' WHERE id = ?", workId);
        dispatcher.deliverPending();

        assertThat(updateNotificationCount(SUBJECT_C3)).isEqualTo(0);
        assertThat(digestCount(SUBJECT_C3, workId)).isEqualTo(0);
    }

    @Test
    void inboxReceiptIsIdempotentOnRedelivery() throws Exception {
        String workId = publishWork("收据幂等作品");
        subscribe(READER_C3, workId, "22:00", "08:00", "UTC", false);
        publishNewChapter(workId, "第二章");
        assertThat(updateNotificationCount(SUBJECT_C3)).isEqualTo(1);

        // 同事件重投：inbox 收据 DuplicateKey → 直接返回，不重复扇出
        var event = jdbc.queryForMap(
                "SELECT event_id, payload FROM outbox_event WHERE event_type = "
                        + "'CONTENT_PUBLISHED_V1' AND payload LIKE ? ORDER BY created_at DESC "
                        + "LIMIT 1", "%" + workId + "%");
        digestConsumer.handle(UUID.fromString((String) event.get("event_id")),
                (String) event.get("payload"));
        assertThat(updateNotificationCount(SUBJECT_C3)).isEqualTo(1);
        assertThat(digestCount(SUBJECT_C3, workId)).isEqualTo(1);
    }
}
