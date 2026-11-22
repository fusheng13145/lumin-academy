package com.leyon.lumin.foundation.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 白名单（2026-11-20 安全审查 16 项）。
 *
 * <p>改前既没有 {@code Access-Control-Allow-Origin} 也没有显式策略：浏览器侧等于
 * "一律不放行"，但也没有任何地方写明允许哪些站点——联调只能靠关浏览器安全策略，
 * 这本身是更危险的做法。本类把允许来源变成**显式配置**：
 *
 * <ul>
 *   <li>{@code lumin.cors.allowed-origins} 逗号分隔的精确来源列表（**不支持** {@code *}）；</li>
 *   <li>未配置或为空 → 不注册任何映射，跨源请求拿不到 CORS 响应头（浏览器照旧拦），
 *       而不是"悄悄全放开"；</li>
 *   <li>{@code allowCredentials=false}：持有令牌走 {@code Authorization} 头，不依赖 Cookie，
 *       因此不需要放开凭证，也避免 {@code *}+凭证的高危组合；</li>
 *   <li>过滤器顺序为最高优先：预检 {@code OPTIONS} 在认证过滤器之前被应答，
 *       不会因为"没有令牌"被 401 挡掉。</li>
 * </ul>
 */
@Configuration
public class CorsPolicyConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(
            @Value("${lumin.cors.allowed-origins:}") String allowedOrigins,
            @Value("${lumin.cors.max-age-seconds:3600}") long maxAgeSeconds) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = parseOrigins(allowedOrigins);
        if (!origins.isEmpty()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(origins);
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key",
                    "If-Match", "If-None-Match", "X-Requested-With", "Accept", "Cache-Control",
                    "X-Lumin-Demo-Subject"));
            config.setExposedHeaders(List.of("ETag", "Retry-After", "Location", "X-Trace-Id"));
            // 不使用 Cookie 会话：不放开凭证，避免 *+credentials 的高危组合
            config.setAllowCredentials(false);
            config.setMaxAge(maxAgeSeconds);
            source.registerCorsConfiguration("/api/**", config);
        }
        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("corsFilter");
        return registration;
    }

    /** 解析来源列表：去空白、去通配符（{@code *} 一律不认，宁可少放行）。 */
    static List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .filter(value -> !"*".equals(value))
                .toList();
    }
}
