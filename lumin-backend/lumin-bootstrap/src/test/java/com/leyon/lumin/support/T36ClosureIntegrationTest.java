package com.leyon.lumin.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T36 注销与匿名化集成测试（03 §8 Tier0 数据留存；T36）：
 * 注销立即公开匿名（CLOSED + 昵称匿名 + closed_at）；6 个月内不推进、
 * 到期推进内部关联匿名化（oidc 关联断开不可逆，审计事实不删）；
 * 法律保全 HELD 暂缓推进、解除后由到期扫描续跑（HELD 行直接匿名化）。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class T36ClosureIntegrationTest extends MySqlIntegrationBase {

    private static final String ADMIN = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ADMIN;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.identity.service.AccountClosureService closureService;

    @org.junit.jupiter.api.BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    @Test
    void closureAnonymizesPublicProfileImmediatelyAndInternalAfterDue() throws Exception {
        // 作者替身注销：公开关联立即断开（CLOSED + 昵称匿名）
        String subject = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.STUB_SUBJECT;
        mockMvc.perform(post("/api/v1/me/closure")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PENDING"))
                .andExpect(jsonPath("$.data.holdId").doesNotExist());
        assertThat(jdbc.queryForObject(
                "SELECT account_status FROM user_profile WHERE id = ?", String.class, subject))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                "SELECT nickname FROM user_profile WHERE id = ?", String.class, subject))
                .isEqualTo("已注销用户");
        assertThat(jdbc.queryForObject(
                "SELECT closed_at IS NOT NULL FROM user_profile WHERE id = ?",
                Integer.class, subject)).isEqualTo(1);
        // 重复注销 → 409（已 CLOSED）
        mockMvc.perform(post("/api/v1/me/closure")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"));
        // 到期前推进 → 不匿名化内部关联
        LocalDateTime beforeDue = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        assertThat(closureService.promoteDue(beforeDue)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM account_closure WHERE user_id = ?", String.class, subject))
                .isEqualTo("PENDING");
        String oidcSubjectBefore = jdbc.queryForObject(
                "SELECT oidc_subject FROM user_profile WHERE id = ?", String.class, subject);
        assertThat(oidcSubjectBefore).startsWith("sub-");
        // 到期推进 → 内部关联匿名化（oidc 断开不可逆）+ 审计事实不删
        LocalDateTime due = LocalDateTime.now(ZoneOffset.UTC).plusMonths(7);
        assertThat(closureService.promoteDue(due)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM account_closure WHERE user_id = ?", String.class, subject))
                .isEqualTo("ANONYMIZED");
        assertThat(jdbc.queryForObject(
                "SELECT oidc_issuer FROM user_profile WHERE id = ?", String.class, subject))
                .isEqualTo("urn:lumin:anonymized");
        assertThat(jdbc.queryForObject(
                "SELECT oidc_subject FROM user_profile WHERE id = ?", String.class, subject))
                .isEqualTo("anon:" + subject);
        // 审计事实不删：注销与匿名化审计均在
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE resource_id = ? AND action = 'account.closed'",
                Integer.class, subject)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE resource_id = ? AND action = 'account.anonymized'",
                Integer.class, subject)).isEqualTo(1);
        // 本人查询推进状态
        mockMvc.perform(get("/api/v1/me/closure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ANONYMIZED"));
    }

    @Test
    void legalHoldDelaysAnonymizationUntilReleased() throws Exception {
        // 注销 → 有 ACTIVE 保全 → HELD（公开匿名已执行，内部匿名化暂缓）
        String subject = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.STUB_SUBJECT;
        mockMvc.perform(post("/api/v1/me/closure")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        String holdId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/legal-holds")
                                .header("X-Lumin-Demo-Subject", ADMIN)
                                .header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"subjectType\":\"USER\",\"subjectId\":\"" + subject
                                        + "\",\"reason\":\"诉讼证据保全\"}"))
                .andExpect(status().isOk()), "id");
        // 有 ACTIVE 保全 → 到期推进跳过（保全暂缓可见：closure 视图 holdActive=true）
        LocalDateTime due = LocalDateTime.now(ZoneOffset.UTC).plusMonths(7);
        assertThat(closureService.promoteDue(due)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM account_closure WHERE user_id = ?", String.class, subject))
                .isEqualTo("PENDING");
        mockMvc.perform(get("/api/v1/me/closure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.holdActive").value(true));
        // 非 ADMIN 立/释保全 → 403
        mockMvc.perform(post("/api/v1/legal-holds")
                        .header("X-Lumin-Demo-Subject",
                                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.REVIEWER_L1A)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"subjectType\":\"USER\",\"subjectId\":\"" + subject
                                + "\",\"reason\":\"越权\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A2001"));
        // 释保全 → 到期扫描续跑（HELD 行直接匿名化，解除后续执行）
        mockMvc.perform(post("/api/v1/legal-holds/" + holdId + "/releases")
                        .header("X-Lumin-Demo-Subject", ADMIN)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("RELEASED"));
        assertThat(closureService.promoteDue(due)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM account_closure WHERE user_id = ?", String.class, subject))
                .isEqualTo("ANONYMIZED");
        assertThat(jdbc.queryForObject(
                "SELECT oidc_issuer FROM user_profile WHERE id = ?", String.class, subject))
                .isEqualTo("urn:lumin:anonymized");
    }
}
