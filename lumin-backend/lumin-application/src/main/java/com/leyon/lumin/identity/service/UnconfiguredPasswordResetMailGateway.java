package com.leyon.lumin.identity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * 默认（未接 SMTP）的邮件通道实现：**明确失败**，绝不假装投递成功。
 *
 * <p>行为：记一条不含重置码与收件人的告警日志，抛
 * {@link PasswordResetMailGateway.MailDeliveryUnavailableException}；
 * 调用方据此把工单置 {@code DELIVERY_FAILED}，由运营人工走客服通道兜底。
 *
 * <p>生产接入方式：提供真实实现并在配置类里优先注册（{@code @Primary}/{@code
 * @ConditionalOnMissingBean}）；{@code mail-mode} 非 {@code unconfigured} 而未提供实现时，
 * 启动阶段即失败，而不是运行期悄悄不投递。
 */
public class UnconfiguredPasswordResetMailGateway implements PasswordResetMailGateway {

    private static final Logger log =
            LoggerFactory.getLogger(UnconfiguredPasswordResetMailGateway.class);

    @Override
    public void send(String recipient, String code, LocalDateTime expiresAt) {
        // 不记收件人、不记重置码：日志不得成为凭证泄露面
        log.error("口令重置邮件投递失败 event=password.reset.mail result=unconfigured "
                + "reason=smtp_not_configured expiresAt={}", expiresAt);
        throw new MailDeliveryUnavailableException(
                "邮件通道未配置（lumin.auth.reset.mail-mode=unconfigured）");
    }
}
