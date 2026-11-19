package com.leyon.lumin.support;

import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.identity.port.AgeVerificationPort;
import com.leyon.lumin.identity.port.GuardianVerificationPort;
import com.leyon.lumin.identity.service.ProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-003 年龄核验 + 监护同意（05 §3.1 verifyAge / 4 consent）：
 * 核验成功才升级、撤销立即降级、仅同意双方可见、管理员不可代建/代撤、
 * 幂等回放与同凭据异键冲突。生产供应商未接，测试以 @Primary 替身覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(UGC003AgeConsentIntegrationTest.FakePorts.class)
class UGC003AgeConsentIntegrationTest extends MySqlIntegrationBase {

    private static final String GUARDIAN = DemoAuthenticationFilter.GUARDIAN;
    private static final String GUARDIAN_SUBJECT = DemoAuthenticationFilter.GUARDIAN_SUBJECT;
    private static final String CHILD = DemoAuthenticationFilter.READER_C2;
    private static final String CHILD_SUBJECT = DemoAuthenticationFilter.READER_C2_SUBJECT;
    private static final String NEWCOMER = DemoAuthenticationFilter.NEWCOMER;
    private static final String NEWCOMER_SUBJECT = DemoAuthenticationFilter.NEWCOMER_SUBJECT;
    private static final String ADMIN = DemoAuthenticationFilter.ADMIN;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProfileService profileService;

    @TestConfiguration(proxyBeanMethods = false)
    static class FakePorts {
        /** 测试用确定性核验：不接受客户端结论；仅按 proofRef 返回受控结果。 */
        @Bean
        @Primary
        AgeVerificationPort ageVerificationPort() {
            return (subject, proofRef, now) -> {
                if ("reject-proof".equals(proofRef)) {
                    return new AgeVerificationPort.Result("test-fixture", "DOCUMENT",
                            "REJECTED", null, now, now.plusYears(1));
                }
                AgeBand band = proofRef.startsWith("minor") ? AgeBand.C2 : AgeBand.C3;
                return new AgeVerificationPort.Result("test-fixture", "DOCUMENT",
                        "VERIFIED", band, now, now.plusYears(1));
            };
        }

        /** 测试用确定性关系回查：仅 GUARDIAN→CHILD 的 guardian-proof* 通过。 */
        @Bean
        @Primary
        GuardianVerificationPort guardianVerificationPort() {
            return (guardianId, childId, scope, proofRef, now) ->
                    GUARDIAN_SUBJECT.equals(guardianId) && CHILD_SUBJECT.equals(childId)
                            && proofRef.startsWith("guardian-proof");
        }
    }

    @Test
    void verifyAgeThenConsentPrivacyAndRevokeDowngrades() throws Exception {
        createProfile(GUARDIAN_SUBJECT, "监护人", "1985-01-01");
        createProfile(CHILD_SUBJECT, "未成年读者", "2010-01-01");

        // 未成年核验 C2：成功写 verified/effective，/me revision 前进
        String proofKey = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/me/age-checks")
                        .header("X-Lumin-Demo-Subject", CHILD)
                        .header("Idempotency-Key", proofKey)
                        .contentType("application/json")
                        .content("{\"proofRef\":\"minor-proof\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resourceType").value("AGE_CHECK"))
                .andExpect(jsonPath("$.data.state").value("VERIFIED"))
                .andExpect(jsonPath("$.data.revision").value(1));
        assertThat(jdbc.queryForObject(
                "SELECT verified_age_band FROM user_profile WHERE id = ?",
                String.class, CHILD_SUBJECT)).isEqualTo("C2");
        assertThat(jdbc.queryForObject(
                "SELECT effective_age_band FROM user_profile WHERE id = ?",
                String.class, CHILD_SUBJECT)).isEqualTo("C2");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM age_verification WHERE user_id = ?",
                Integer.class, CHILD_SUBJECT)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/me").header("X-Lumin-Demo-Subject", CHILD))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.data.effectiveAgeBand").value("C2"));

        // 同键同载荷回放；同凭据异键 409/B2001
        mockMvc.perform(post("/api/v1/me/age-checks")
                        .header("X-Lumin-Demo-Subject", CHILD)
                        .header("Idempotency-Key", proofKey)
                        .contentType("application/json")
                        .content("{\"proofRef\":\"minor-proof\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/me/age-checks")
                        .header("X-Lumin-Demo-Subject", CHILD)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"proofRef\":\"minor-proof\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"));

        // 监护人须先完成成年核验（C3），否则不能代建同意
        mockMvc.perform(post("/api/v1/me/age-checks")
                        .header("X-Lumin-Demo-Subject", GUARDIAN)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"proofRef\":\"adult-proof\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("VERIFIED"));
        mockMvc.perform(get("/api/v1/me").header("X-Lumin-Demo-Subject", GUARDIAN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveAgeBand").value("C3"));

        // 监护人创建 PUBLICATION 同意
        MvcResult created = mockMvc.perform(post("/api/v1/guardian-consents")
                        .header("X-Lumin-Demo-Subject", GUARDIAN)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"childId\":\"" + CHILD_SUBJECT
                                + "\",\"proofRef\":\"guardian-proof-1\",\"scope\":\"PUBLICATION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resourceType").value("CONSENT"))
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andReturn();
        String consentId = jdbc.queryForObject(
                "SELECT id FROM guardian_consent WHERE guardian_id = ? AND child_id = ?",
                String.class, GUARDIAN_SUBJECT, CHILD_SUBJECT);

        // 仅同意双方可见：监护人/child 各 1 条，ADMIN 列表为空且详情 404
        mockMvc.perform(get("/api/v1/guardian-consents")
                        .header("X-Lumin-Demo-Subject", GUARDIAN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].request.childId").value(CHILD_SUBJECT))
                .andExpect(jsonPath("$.data.items[0].request.proofRef")
                        .value("guardian-proof-1"));
        mockMvc.perform(get("/api/v1/guardian-consents")
                        .header("X-Lumin-Demo-Subject", CHILD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mockMvc.perform(get("/api/v1/guardian-consents")
                        .header("X-Lumin-Demo-Subject", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        mockMvc.perform(get("/api/v1/guardian-consents/" + consentId)
                        .header("X-Lumin-Demo-Subject", ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("B2004"));
        mockMvc.perform(get("/api/v1/guardian-consents/" + consentId)
                        .header("X-Lumin-Demo-Subject", CHILD))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.reviews.length()").value(1));

        // 非监护人不得代建；关系核验失败 A2002；同凭据异键 B2001
        mockMvc.perform(post("/api/v1/guardian-consents")
                        .header("X-Lumin-Demo-Subject", NEWCOMER)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"childId\":\"" + CHILD_SUBJECT
                                + "\",\"proofRef\":\"guardian-proof-2\",\"scope\":\"PUBLICATION\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A2001"));
        mockMvc.perform(post("/api/v1/guardian-consents")
                        .header("X-Lumin-Demo-Subject", GUARDIAN)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"childId\":\"" + CHILD_SUBJECT
                                + "\",\"proofRef\":\"bad-proof\",\"scope\":\"PUBLICATION\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A2002"));
        mockMvc.perform(post("/api/v1/guardian-consents")
                        .header("X-Lumin-Demo-Subject", GUARDIAN)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"childId\":\"" + CHILD_SUBJECT
                                + "\",\"proofRef\":\"guardian-proof-1\",\"scope\":\"PUBLICATION\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"));

        // 撤销：If-Match=1，状态 REVOKED、revision 2，未成年有效层降 C4
        mockMvc.perform(post("/api/v1/guardian-consents/" + consentId + "/revocations")
                        .header("X-Lumin-Demo-Subject", GUARDIAN)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"reason\":\"监护人撤回同意\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("REVOKED"))
                .andExpect(jsonPath("$.data.revision").value(2));
        mockMvc.perform(get("/api/v1/me").header("X-Lumin-Demo-Subject", CHILD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveAgeBand").value("C4"));
        mockMvc.perform(get("/api/v1/guardian-consents/" + consentId)
                        .header("X-Lumin-Demo-Subject", GUARDIAN))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.data.state").value("REVOKED"))
                .andExpect(jsonPath("$.data.reviews.length()").value(2));

        // 重复撤销 409；缺 If-Match 428
        mockMvc.perform(post("/api/v1/guardian-consents/" + consentId + "/revocations")
                        .header("X-Lumin-Demo-Subject", GUARDIAN)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"2\"")
                        .contentType("application/json")
                        .content("{\"reason\":\"再次撤销\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"));
        mockMvc.perform(post("/api/v1/guardian-consents/" + consentId + "/revocations")
                        .header("X-Lumin-Demo-Subject", GUARDIAN)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"reason\":\"缺版本\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("P1002"));

        // 重新同意恢复 C2（新凭据，旧撤销记录保留）
        mockMvc.perform(post("/api/v1/guardian-consents")
                        .header("X-Lumin-Demo-Subject", GUARDIAN)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"childId\":\"" + CHILD_SUBJECT
                                + "\",\"proofRef\":\"guardian-proof-2\",\"scope\":\"PUBLICATION\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").header("X-Lumin-Demo-Subject", CHILD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveAgeBand").value("C2"));
        assertThat((String) created.getResponse().getContentAsString()).contains(consentId);
    }

    @Test
    void rejectedVerificationDoesNotUpgrade() throws Exception {
        createProfile(NEWCOMER_SUBJECT, "成年读者", "2000-01-01");
        mockMvc.perform(post("/api/v1/me/age-checks")
                        .header("X-Lumin-Demo-Subject", NEWCOMER)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"proofRef\":\"reject-proof\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("REJECTED"));
        assertThat(jdbc.queryForObject(
                "SELECT verified_age_band FROM user_profile WHERE id = ?",
                String.class, NEWCOMER_SUBJECT)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT effective_age_band FROM user_profile WHERE id = ?",
                String.class, NEWCOMER_SUBJECT)).isEqualTo("C4");
        mockMvc.perform(get("/api/v1/me").header("X-Lumin-Demo-Subject", NEWCOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveAgeBand").value("C4"));
    }

    private void createProfile(String subject, String nickname, String birthDate) {
        profileService.createProfile(subject, nickname, birthDate, null,
                LocalDate.of(2026, 11, 19), LocalDateTime.of(2026, 11, 19, 0, 0));
    }
}
