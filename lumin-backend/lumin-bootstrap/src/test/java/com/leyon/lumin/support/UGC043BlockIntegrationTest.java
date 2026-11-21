package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-043 互动安全集成测试（docs/13 M2 一阶段；V19）：拉黑/解除/列表
 * （个人安全自服务——READER 即可用）；不能拉黑自己 400；目标不存在统一
 * 404 不枚举；重复拉黑 409；解除幂等；跨账号隔离；InteractionGatePort
 * 单向裁决（A 拉黑 B 仅 A→B 方向为真）；审计留痕（追加不删）。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC043BlockIntegrationTest extends MySqlIntegrationBase {

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
    private static final String SUBJECT_C3 =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3_SUBJECT;
    private static final String SUBJECT_C1 =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C1_SUBJECT;
    private static final String SUBJECT_AUTHOR =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.STUB_SUBJECT;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.foundation.port.InteractionGatePort interactionGate;

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
        MySqlFixtures.insertReaderProfiles(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private org.springframework.test.web.servlet.ResultActions block(
            String subject, String target, String reason) throws Exception {
        String reasonJson = reason == null ? "null" : "\"" + reason + "\"";
        return mockMvc.perform(post("/api/v1/me/blocks")
                .header("X-Lumin-Demo-Subject", subject)
                .header("Idempotency-Key", uuidKey())
                .contentType("application/json")
                .content("{\"subjectId\":\"" + target + "\",\"reason\":" + reasonJson + "}"));
    }

    @Test
    void blockUnblockListFlowForAnyAuthenticatedUser() throws Exception {
        // 个人安全自服务：READER 直接可用
        block(READER_C3, SUBJECT_C1, "骚扰").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blocked").value(true));
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_block WHERE blocker_id = ? AND blocked_id = ?",
                Integer.class, SUBJECT_C3, SUBJECT_C1);
        assertThat(rows).isEqualTo(1);
        // 列表仅本人可见（含被拉黑人昵称）
        mockMvc.perform(get("/api/v1/me/blocks")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].subjectId").value(SUBJECT_C1))
                .andExpect(jsonPath("$.data[0].nickname").value("读者C1"))
                .andExpect(jsonPath("$.data[0].reason").value("骚扰"));
        // 被拉黑方的列表不受影响（单向关系）
        mockMvc.perform(get("/api/v1/me/blocks")
                        .header("X-Lumin-Demo-Subject", READER_C1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        // 解除幂等：两次 DELETE 均 200，行清空
        mockMvc.perform(delete("/api/v1/me/blocks/" + SUBJECT_C1)
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blocked").value(false));
        mockMvc.perform(delete("/api/v1/me/blocks/" + SUBJECT_C1)
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        Integer after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_block WHERE blocker_id = ?",
                Integer.class, SUBJECT_C3);
        assertThat(after).isEqualTo(0);
        // 审计留痕（追加不删）：拉黑 1 + 解除 2
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE resource_type = 'user_block' "
                        + "AND actor_id = ?", Integer.class, SUBJECT_C3);
        assertThat(audits).isEqualTo(3);
    }

    @Test
    void selfBlockAndUnknownTargetAreRejected() throws Exception {
        // 不能拉黑自己
        block(READER_C3, SUBJECT_C3, null).andExpect(status().isBadRequest());
        // 目标不存在统一 404（不枚举存在性）
        block(READER_C3, UUID.randomUUID().toString(), null)
                .andExpect(status().isNotFound());
        // 原因超长 → 400
        block(READER_C3, SUBJECT_C1, "长".repeat(101)).andExpect(status().isBadRequest());
        assertThat(interactionGate.isBlocked(SUBJECT_C3, SUBJECT_C1)).isFalse();
    }

    @Test
    void duplicateBlockIsConflictAndGateIsOneDirectional() throws Exception {
        block(READER_C3, SUBJECT_AUTHOR, "重复拉黑目标").andExpect(status().isOk());
        // 重复拉黑 → 409
        block(READER_C3, SUBJECT_AUTHOR, null).andExpect(status().isConflict());
        // 闸：A 拉黑 B → A→B 方向为真
        assertThat(interactionGate.isBlocked(SUBJECT_C3, SUBJECT_AUTHOR)).isTrue();
        // 单向关系：B 未拉黑 A → B→A 方向为假
        assertThat(interactionGate.isBlocked(SUBJECT_AUTHOR, SUBJECT_C3)).isFalse();
        // 解除后闸复位
        mockMvc.perform(delete("/api/v1/me/blocks/" + SUBJECT_AUTHOR)
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        assertThat(interactionGate.isBlocked(SUBJECT_C3, SUBJECT_AUTHOR)).isFalse();
    }
}
