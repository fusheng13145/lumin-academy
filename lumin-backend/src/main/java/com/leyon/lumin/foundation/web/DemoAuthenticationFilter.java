package com.leyon.lumin.foundation.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 骨架认证替身（**临时实现，如实登记**）：OIDC Authorization Code + PKCE 接入
 * （05 §2）之前的开发骨架。当前固定注入合成作者主体（07 §1.1 作者A 口径），
 * **忽略一切客户端提供的身份头/请求体自报身份**（05 §2：不接受 X-User-Id 等）。
 * 真实身份接入落地时本过滤器整体移除，替换为 Bearer 令牌校验过滤器。
 */
@Component
public class DemoAuthenticationFilter extends OncePerRequestFilter {

    /** 请求属性键：当前主体（服务端权威注入，非客户端自报）。 */
    public static final String ATTRIBUTE_SUBJECT = "lumin.subject";

    /** 骨架阶段固定合成主体（UUIDv4 合成值，测试夹具据此建档）。 */
    public static final String STUB_SUBJECT =
            UUID.fromString("9f8f8f8f-0000-4000-8000-0000000000a1").toString();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        request.setAttribute(ATTRIBUTE_SUBJECT, STUB_SUBJECT);
        chain.doFilter(request, response);
    }
}
