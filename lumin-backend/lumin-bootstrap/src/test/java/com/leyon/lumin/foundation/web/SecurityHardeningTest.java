package com.leyon.lumin.foundation.web;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.startup.SecurityStartupValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全加固取证（2026-11-20 安全与质量审查修复）：
 * <ol>
 *   <li>启动门禁：空签名密钥 / 非开发环境 demo 认证 → 启动即失败；</li>
 *   <li>安全响应头：nosniff / frameOptions / CSP / Referrer-Policy 全量下发，
 *       HSTS 仅在 HTTPS 部署下发；</li>
 *   <li>traceId 一致性：信封 traceId 必须等于 MDC 中的 traceId（原实现键名不一致
 *       导致回落到随机 UUID，错误日志与响应无法关联）。</li>
 * </ol>
 * 本类为纯单元取证，不启动 Spring 容器、不连数据库。
 */
class SecurityHardeningTest {

    @Test
    void startupRejectsBlankCursorKey() {
        assertThrows(IllegalStateException.class,
                () -> validator("", "session", "production", "unconfigured"),
                "空游标密钥必须拒绝启动");
        assertThrows(IllegalStateException.class,
                () -> validator("   ", "session", "dev", "unconfigured"),
                "空白游标密钥必须拒绝启动（含开发环境）");
    }

    @Test
    void startupRejectsDemoAuthOutsideDev() {
        assertThrows(IllegalStateException.class,
                () -> validator("k", "demo", "production", "unconfigured"),
                "生产环境 demo 认证必须拒绝启动");
        // 开发/测试环境保留 demo 骨架
        validator("k", "demo", "dev", "unconfigured");
        // 生产环境显式 session 放行
        validator("k", "session", "production", "unconfigured");
    }

    @Test
    void startupRejectsResetMailModeWithoutImplementation() {
        // 配了 smtp 却只有 unconfigured 兜底实现 → 启动失败（不静默不投递）
        assertThrows(IllegalStateException.class,
                () -> validator("k", "session", "production", "smtp"),
                "mail-mode 与实现不一致必须拒绝启动");
        // unconfigured 模式 + 兜底实现 = 合法（工单转人工）
        validator("k", "session", "production", "unconfigured");
    }

    private static SecurityStartupValidator validator(String cursorKey, String authMode,
                                                      String environment, String mailMode) {
        return new SecurityStartupValidator(cursorKey, authMode, environment, mailMode,
                new com.leyon.lumin.identity.service.UnconfiguredPasswordResetMailGateway());
    }

    @Test
    void securityHeadersAreAppliedAndHstsOnlyUnderHttps() throws Exception {
        MockHttpServletRequest plain = new MockHttpServletRequest("GET", "/api/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SecurityHeadersFilter(false).doFilter(plain, response, (req, res) -> { });

        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertTrue(response.getHeader("Content-Security-Policy").contains("frame-ancestors 'none'"));
        assertNull(response.getHeader("Strict-Transport-Security"),
                "纯 HTTP 部署不得下发 HSTS（避免把浏览器锁死到不可达的 HTTPS）");

        MockHttpServletResponse httpsResponse = new MockHttpServletResponse();
        new SecurityHeadersFilter(true).doFilter(plain, httpsResponse, (req, res) -> { });
        assertTrue(httpsResponse.getHeader("Strict-Transport-Security").contains("max-age=31536000"));
    }

    @Test
    void envelopeTraceIdComesFromMdc() {
        MDC.put("traceId", "trace-under-test");
        try {
            assertEquals("trace-under-test", ApiEnvelope.ok(null).traceId());
            assertEquals("trace-under-test", ApiEnvelope.currentTraceId());
        } finally {
            MDC.remove("traceId");
        }
    }
}
