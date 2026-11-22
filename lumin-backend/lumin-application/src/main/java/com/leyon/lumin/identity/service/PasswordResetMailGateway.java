package com.leyon.lumin.identity.service;

import java.time.LocalDateTime;

/**
 * 口令重置邮件投递端口（V24）。
 *
 * <p><strong>接缝说明（如实登记）</strong>：本机与 CI 均无 SMTP 与邮件库（Maven Central
 * 不可达、无 {@code spring-boot-starter-mail} 制品），因此本轮只落地**端口 + 两种实现**：
 * <ul>
 *   <li>生产：由部署侧提供真实实现（SMTP / 邮件服务商 SDK），注入同名 Bean 即生效；</li>
 *   <li>默认（{@code UnconfiguredPasswordResetMailGateway}）：**明确失败**，工单落
 *       {@code DELIVERY_FAILED} 交运营人工处理，而不是假装发送成功。</li>
 * </ul>
 * 明文重置码只在 {@link #send} 的入参里出现一次：不落库、不写日志、不回响应。
 */
public interface PasswordResetMailGateway {

    /**
     * 投递重置码。
     *
     * @param recipient 收件邮箱（仅用于投递，不写日志）
     * @param code      一次性明文重置码（调用方已只存摘要）
     * @param expiresAt 失效时间（供邮件正文使用）
     * @throws MailDeliveryUnavailableException 通道未配置/不可用时抛出
     */
    void send(String recipient, String code, LocalDateTime expiresAt);

    /** 邮件通道不可用（属于部署态缺失，不是业务错误）。 */
    class MailDeliveryUnavailableException extends RuntimeException {
        public MailDeliveryUnavailableException(String message) {
            super(message);
        }
    }
}
