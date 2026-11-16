package com.leyon.lumin.foundation.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * 骨架身份替身过滤器（**临时实现，如实登记**）：OIDC Authorization Code + PKCE 接入
 * （05 §2）之前的开发骨架。替身集合为**有界枚举**（固定 UUID，测试夹具据此建档），
 * 请求经可选头 `X-Lumin-Demo-Subject`（取值为替身名）选择，**不接受任意自报 ID、
 * 不接受 X-User-Id 等真实身份头**（05 §2 语义不变）；未指定时默认作者替身。
 * 真实身份接入落地时本过滤器整体移除。
 */
@Component
public class DemoAuthenticationFilter extends OncePerRequestFilter {

    /** 请求属性键：当前主体（服务端权威注入，非客户端自报）。 */
    public static final String ATTRIBUTE_SUBJECT = "lumin.subject";

    /** 替身集合成员标识（请求头取值）。 */
    public static final String AUTHOR = "AUTHOR";
    public static final String REVIEWER_L1A = "REVIEWER_L1A";
    public static final String REVIEWER_L2A = "REVIEWER_L2A";

    public static final String STUB_SUBJECT =
            UUID.fromString("9f8f8f8f-0000-4000-8000-0000000000a1").toString();
    public static final String REVIEWER_L1A_SUBJECT =
            UUID.fromString("8f8f8f8f-0000-4000-8000-0000000000b2").toString();
    public static final String REVIEWER_L2A_SUBJECT =
            UUID.fromString("7f7f7f7f-0000-4000-8000-0000000000c3").toString();

    /** 有界替身映射：名称 → 主体 UUID（仅集合内成员可用）。 */
    private static final Map<String, String> STUB_SUBJECTS = Map.of(
            AUTHOR, STUB_SUBJECT,
            REVIEWER_L1A, REVIEWER_L1A_SUBJECT,
            REVIEWER_L2A, REVIEWER_L2A_SUBJECT);

    private static final Map<String, String> STUB_ROLES = Map.of(
            AUTHOR, "AUTHOR",
            REVIEWER_L1A, "REVIEWER_L1",
            REVIEWER_L2A, "REVIEWER_L2");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requested = request.getHeader("X-Lumin-Demo-Subject");
        String subject = requested == null ? STUB_SUBJECT
                : STUB_SUBJECTS.getOrDefault(requested, STUB_SUBJECT);
        String stubName = requested == null ? AUTHOR
                : STUB_SUBJECTS.containsKey(requested) ? requested : AUTHOR;
        request.setAttribute(ATTRIBUTE_SUBJECT, subject);
        request.setAttribute(ATTRIBUTE_STUB_NAME, stubName);
        request.setAttribute(ATTRIBUTE_ROLE, STUB_ROLES.get(stubName));
        chain.doFilter(request, response);
    }

    public static final String ATTRIBUTE_STUB_NAME = "lumin.stubName";
    /** 替身角色（骨架授权口径：REVIEWER_L1A→REVIEWER_L1 等；真实授权随授予链路接线）。 */
    public static final String ATTRIBUTE_ROLE = "lumin.role";
}
