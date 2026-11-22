package com.leyon.lumin.configuration;

import com.leyon.lumin.identity.service.PasswordResetMailGateway;
import com.leyon.lumin.identity.service.UnconfiguredPasswordResetMailGateway;
import com.leyon.lumin.identity.mail.SmtpPasswordResetMailGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 口令重置邮件通道装配（V24）。
 *
 * <p>{@code @ConditionalOnMissingBean}：只有部署侧（或测试）没有提供
 * {@link PasswordResetMailGateway} 实现时才装本配置的 Bean——{@code mail-mode=smtp}
 * 装真实 SMTP 网关，其余模式装"明确失败"兜底。模式与实现的一致性由
 * {@code SecurityStartupValidator} 在启动阶段校验：配了 {@code smtp} 但容器里仍是兜底，
 * 或 SMTP 参数不完整/不安全（带口令却不开加密），都会在启动时失败。
 */
@Configuration
public class PasswordResetMailConfig {

    @Bean
    @ConditionalOnMissingBean(PasswordResetMailGateway.class)
    public PasswordResetMailGateway passwordResetMailGateway(
            @Value("${lumin.auth.reset.mail-mode:unconfigured}") String mailMode,
            @Value("${lumin.auth.reset.mail.host:}") String host,
            @Value("${lumin.auth.reset.mail.port:587}") int port,
            @Value("${lumin.auth.reset.mail.username:}") String username,
            @Value("${lumin.auth.reset.mail.password:}") String password,
            @Value("${lumin.auth.reset.mail.from:}") String from,
            @Value("${lumin.auth.reset.mail.starttls:true}") boolean starttls,
            @Value("${lumin.auth.reset.mail.ssl:false}") boolean ssl,
            @Value("${lumin.auth.reset.mail.timeout-seconds:10}") int timeoutSeconds) {
        if (!"smtp".equalsIgnoreCase(mailMode == null ? "" : mailMode.trim())) {
            return new UnconfiguredPasswordResetMailGateway();
        }
        return new SmtpPasswordResetMailGateway(host, port, username, password, from, starttls,
                ssl, Duration.ofSeconds(timeoutSeconds));
    }
}
