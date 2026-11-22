package com.leyon.lumin.foundation.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安全响应头过滤器（2026-11-20 安全审查修复：原实现仅有资产端点一处 nosniff）。
 *
 * <p>只写静态响应头，不涉及任何认证/加密逻辑；HSTS 仅在 HTTPS 部署下下发
 * （{@code lumin.deployment.https=true} 或请求本身为 secure），避免在纯 HTTP
 * 环境把客户端锁死到不可用的 HTTPS 地址。
 *
 * <p>不设置 {@code Cross-Origin-Resource-Policy}：前端与 API 当前为不同源部署，
 * {@code same-origin} 会直接切断封面/证据图片加载，属"防御过当"。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'";
    private static final String PERMISSIONS_POLICY =
            "geolocation=(), microphone=(), camera=()";
    private static final String HSTS = "max-age=31536000; includeSubDomains";

    private final boolean httpsDeployment;

    public SecurityHeadersFilter(@Value("${lumin.deployment.https:false}") boolean httpsDeployment) {
        this.httpsDeployment = httpsDeployment;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // setHeader 而非 addHeader：同一请求内重复执行不产生多值
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        if (httpsDeployment || request.isSecure()) {
            response.setHeader("Strict-Transport-Security", HSTS);
        }
        chain.doFilter(request, response);
    }
}
