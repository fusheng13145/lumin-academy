package com.leyon.lumin.foundation.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 写操作限流过滤器（docs/13 UGC-029 一阶段；O25 反滥用；6.9 发布频控/举报限流）：
 * 对写方法（POST/PUT/PATCH/DELETE）按 **主体 + 路径组** 滑动窗口限流——
 * 超限 → 429/A2003 + Retry-After（窗口剩余秒数）。读方法不限。
 * 内存滑动窗口为 **单实例 P0 口径**（如实登记）：多副本部署随网关级限流（O25）
 * 替换；`lumin.rate-limit.enabled=false`（默认，开发/测试关闭）时本过滤器不创建，
 * 生产部署 profile 显式开启。
 */
@Component
@org.springframework.core.annotation.Order(3)
@ConditionalOnProperty(name = "lumin.rate-limit.enabled", havingValue = "true")
public class RateLimitFilter extends OncePerRequestFilter {

    private final int writesPerMinute;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${lumin.rate-limit.writes-per-minute:120}") int writesPerMinute,
            ObjectMapper objectMapper) {
        this.writesPerMinute = writesPerMinute;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String method = request.getMethod();
        boolean write = HttpMethod.POST.matches(method) || HttpMethod.PUT.matches(method)
                || HttpMethod.PATCH.matches(method) || HttpMethod.DELETE.matches(method);
        if (!write) {
            chain.doFilter(request, response);
            return;
        }
        // 主体键取认证过滤器注入的属性；缺失（登录/登出等豁免端点）回落客户端地址，
        // 避免 String.valueOf(null) → "null" 让所有匿名请求共用一个桶（2026-11-20 修复）。
        Object subjectAttribute = request.getAttribute(
                DemoAuthenticationFilter.ATTRIBUTE_SUBJECT);
        String subject = subjectAttribute != null
                ? subjectAttribute.toString() : "ip:" + request.getRemoteAddr();
        // 路径组：资源前两段（/api/v1/<group>），同类资源共享配额
        String[] segments = request.getRequestURI().split("/");
        String group = segments.length >= 4 ? segments[3] : "other";
        Instant now = Instant.now();
        String key = subject + ":" + group;
        Deque<Instant> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minusSeconds(60))) {
                window.pollFirst();
            }
            if (window.size() >= writesPerMinute) {
                long retryAfter = Math.max(1, 60 - java.time.Duration.between(
                        window.peekFirst(), now).getSeconds());
                response.setStatus(com.leyon.lumin.foundation.ErrorCodes.ErrorCode.A2003
                        .httpStatus());
                response.setHeader("Retry-After", String.valueOf(retryAfter));
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        com.leyon.lumin.foundation.contract.ApiEnvelope.error(
                                com.leyon.lumin.foundation.ErrorCodes.ErrorCode.A2003)));
                return;
            }
            window.addLast(now);
        }
        chain.doFilter(request, response);
    }
}
