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
 * 身份过滤器（UGC-002 一阶段）：持有令牌优先——`Authorization: Bearer` 经
 * {@link com.leyon.lumin.identity.service.AuthSessionService} 服务端权威解析
 * （缺失/伪造/过期/撤销一律 401/A0001）；`lumin.auth.mode=session` 时演示身份头
 * 被忽略（生产无法使用演示头的结构性闸门），`demo` 模式（开发/测试骨架）保留
 * 有界替身头 `X-Lumin-Demo-Subject`（不接受任意自报 ID、不接受 X-User-Id 等
 * 真实身份头）。OIDC Authorization Code + PKCE 为签发端的后续替换（UGC-002
 * 后续阶段），解析/撤销语义不变。
 */
@Component
public class DemoAuthenticationFilter extends OncePerRequestFilter {

    /** 请求属性键：当前主体（服务端权威注入，非客户端自报）。 */
    public static final String ATTRIBUTE_SUBJECT = "lumin.subject";

    /** 替身集合成员标识（请求头取值）。 */
    public static final String AUTHOR = "AUTHOR";
    public static final String REVIEWER_L1A = "REVIEWER_L1A";
    public static final String REVIEWER_L2A = "REVIEWER_L2A";
    /** 读者替身（T27 读者链矩阵演示口径：C0–C4 各一，无任何特权角色）。 */
    public static final String READER_C0 = "READER_C0";
    public static final String READER_C1 = "READER_C1";
    public static final String READER_C2 = "READER_C2";
    public static final String READER_C3 = "READER_C3";
    public static final String READER_C4 = "READER_C4";
    /** 首次登录替身（尚无 profile；用于 createProfile 正向链路与 /me 建档前 404）。 */
    public static final String NEWCOMER = "NEWCOMER";
    /** 配置域替身（05 §3.4：提案 OPERATOR/REVIEWER_L2、审批 ADMIN）。 */
    public static final String OPERATOR = "OPERATOR";
    public static final String ADMIN = "ADMIN";
    /** 审计读取替身（05 §3.4：AUDITOR 授权作用域只读）。 */
    public static final String AUDITOR = "AUDITOR";

    public static final String STUB_SUBJECT =
            UUID.fromString("9f8f8f8f-0000-4000-8000-0000000000a1").toString();
    public static final String REVIEWER_L1A_SUBJECT =
            UUID.fromString("8f8f8f8f-0000-4000-8000-0000000000b2").toString();
    public static final String REVIEWER_L2A_SUBJECT =
            UUID.fromString("7f7f7f7f-0000-4000-8000-0000000000c3").toString();
    public static final String OPERATOR_SUBJECT =
            UUID.fromString("5e8f8f8f-0000-4000-8000-0000000000e1").toString();
    public static final String ADMIN_SUBJECT =
            UUID.fromString("4d8f8f8f-0000-4000-8000-0000000000f2").toString();
    public static final String AUDITOR_SUBJECT =
            UUID.fromString("3c8f8f8f-0000-4000-8000-0000000000a9").toString();
    public static final String READER_C0_SUBJECT =
            UUID.fromString("6f8f8f8f-0000-4000-8000-00000000d001").toString();
    public static final String READER_C1_SUBJECT =
            UUID.fromString("6f8f8f8f-0000-4000-8000-00000000d002").toString();
    public static final String READER_C2_SUBJECT =
            UUID.fromString("6f8f8f8f-0000-4000-8000-00000000d003").toString();
    public static final String READER_C3_SUBJECT =
            UUID.fromString("6f8f8f8f-0000-4000-8000-00000000d004").toString();
    public static final String READER_C4_SUBJECT =
            UUID.fromString("6f8f8f8f-0000-4000-8000-00000000d005").toString();
    public static final String NEWCOMER_SUBJECT =
            UUID.fromString("6f8f8f8f-0000-4000-8000-00000000d006").toString();

    /** 有界替身映射：名称 → 主体 UUID（仅集合内成员可用）。 */
    private static final Map<String, String> STUB_SUBJECTS = Map.ofEntries(
            Map.entry(AUTHOR, STUB_SUBJECT),
            Map.entry(REVIEWER_L1A, REVIEWER_L1A_SUBJECT),
            Map.entry(REVIEWER_L2A, REVIEWER_L2A_SUBJECT),
            Map.entry(READER_C0, READER_C0_SUBJECT),
            Map.entry(READER_C1, READER_C1_SUBJECT),
            Map.entry(READER_C2, READER_C2_SUBJECT),
            Map.entry(READER_C3, READER_C3_SUBJECT),
            Map.entry(READER_C4, READER_C4_SUBJECT),
            Map.entry(NEWCOMER, NEWCOMER_SUBJECT),
            Map.entry(OPERATOR, OPERATOR_SUBJECT),
            Map.entry(ADMIN, ADMIN_SUBJECT),
            Map.entry(AUDITOR, AUDITOR_SUBJECT));

    private static final Map<String, String> STUB_ROLES = Map.ofEntries(
            Map.entry(AUTHOR, "AUTHOR"),
            Map.entry(REVIEWER_L1A, "REVIEWER_L1"),
            Map.entry(REVIEWER_L2A, "REVIEWER_L2"),
            Map.entry(READER_C0, "READER"),
            Map.entry(READER_C1, "READER"),
            Map.entry(READER_C2, "READER"),
            Map.entry(READER_C3, "READER"),
            Map.entry(READER_C4, "READER"),
            Map.entry(NEWCOMER, "READER"),
            Map.entry(OPERATOR, "OPERATOR"),
            Map.entry(ADMIN, "ADMIN"),
            Map.entry(AUDITOR, "AUDITOR"));

    private final com.leyon.lumin.identity.service.AuthSessionService sessionService;
    private final com.leyon.lumin.identity.repository.RoleGrantMapper roleGrantMapper;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final String authMode;

    public DemoAuthenticationFilter(
            com.leyon.lumin.identity.service.AuthSessionService sessionService,
            com.leyon.lumin.identity.repository.RoleGrantMapper roleGrantMapper,
            tools.jackson.databind.ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${lumin.auth.mode:demo}")
            String authMode) {
        this.sessionService = sessionService;
        this.roleGrantMapper = roleGrantMapper;
        this.objectMapper = objectMapper;
        this.authMode = authMode;
    }

    /** 有效角色解析（UGC-004）：已授予角色优先于替身基础角色（服务端权威，不信客户端）。 */
    private String effectiveRole(String subject, String baseRole) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        var grants = roleGrantMapper.findActiveBySubject(subject, now);
        return grants.isEmpty() ? baseRole : grants.get(0).role();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 登录/登出端点豁免：签发入口自身不要求已有会话；登出幂等——
        // 已撤销令牌的重复登出由服务层幂等处理（不重复产生副作用）
        if ("/api/v1/auth/sessions".equals(request.getRequestURI())
                || "/api/v1/auth/sessions/revocations".equals(request.getRequestURI())) {
            if ("POST".equals(request.getMethod())) {
                chain.doFilter(request, response);
                return;
            }
        }
        // UGC-002 一阶段：持有令牌优先（两种模式一致，服务端权威——不存原文、可撤销）
        String bearer = bearerOf(request.getHeader("Authorization"));
        if (bearer != null) {
            com.leyon.lumin.identity.repository.AuthSessionRow session =
                    sessionService.resolve(bearer);
            if (session == null) {
                writeAuthRequired(response);
                return;
            }
            request.setAttribute(ATTRIBUTE_SUBJECT, session.subject());
            request.setAttribute(ATTRIBUTE_STUB_NAME, session.stubName());
            request.setAttribute(ATTRIBUTE_ROLE,
                    effectiveRole(session.subject(), session.roleContext()));
            chain.doFilter(request, response);
            return;
        }
        // `lumin.auth.mode=session`：演示身份头被忽略——生产无法使用演示头（结构性闸门）
        if ("session".equals(authMode)) {
            writeAuthRequired(response);
            return;
        }
        // demo 模式（开发/测试骨架）：有界替身头选择主体；未指定默认作者替身
        String requested = request.getHeader("X-Lumin-Demo-Subject");
        String subject = requested == null ? STUB_SUBJECT
                : STUB_SUBJECTS.getOrDefault(requested, STUB_SUBJECT);
        String stubName = requested == null ? AUTHOR
                : STUB_SUBJECTS.containsKey(requested) ? requested : AUTHOR;
        request.setAttribute(ATTRIBUTE_SUBJECT, subject);
        request.setAttribute(ATTRIBUTE_STUB_NAME, stubName);
        request.setAttribute(ATTRIBUTE_ROLE, effectiveRole(subject, STUB_ROLES.get(stubName)));
        chain.doFilter(request, response);
    }

    /** 从 Authorization 头提取持有令牌（非 Bearer 形态返回 null）。 */
    private static String bearerOf(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    /** 401 信封（过滤器层直接写出——早于 DispatcherServlet，异常解析器不覆盖）。 */
    private void writeAuthRequired(HttpServletResponse response) throws IOException {
        response.setStatus(com.leyon.lumin.foundation.ErrorCodes.ErrorCode.A0001.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                com.leyon.lumin.foundation.contract.ApiEnvelope.error(
                        com.leyon.lumin.foundation.ErrorCodes.ErrorCode.A0001)));
    }

    public static final String ATTRIBUTE_STUB_NAME = "lumin.stubName";
    /** 替身角色（骨架授权口径：REVIEWER_L1A→REVIEWER_L1 等；真实授权随授予链路接线）。 */
    public static final String ATTRIBUTE_ROLE = "lumin.role";
}
