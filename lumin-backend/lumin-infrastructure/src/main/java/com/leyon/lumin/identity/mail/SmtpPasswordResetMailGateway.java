package com.leyon.lumin.identity.mail;

import com.leyon.lumin.identity.service.PasswordResetMailGateway;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * 真实 SMTP 邮件通道（V24 收尾；2026-11-20 审查 04 项"邮件通道必须有"）。
 *
 * <p>只做一件事：把一次性重置码投到收件人邮箱。**代码本身不拼协议**——SMTP 会话、
 * MIME 编码、AUTH、STARTTLS 全部交给 {@code jakarta.mail}（Spring Boot BOM 管理的成熟实现）。
 *
 * <p>安全口径：
 * <ol>
 *   <li>收件人先过严格语法校验（禁空白/CR/LF/@ 之外的控制字符），再用
 *       {@link InternetAddress#InternetAddress(String, boolean)} 严格解析——防头注入；</li>
 *   <li>配了口令却不加密（既无 STARTTLS 也无 SMTPS）→ **构造即失败**，不允许明文发凭据；</li>
 *   <li>STARTTLS 用 {@code starttls.required=true}：服务器不支持就失败，不静默降级成明文；</li>
 *   <li>{@code ssl.checkserveridentity=true}：校验服务器证书主机名，防中间人；</li>
 *   <li>连接/读/写超时都设上限，避免请求线程被挂死（重置是同步调用）；</li>
 *   <li>日志只记结果，**不记收件人、不记重置码**；异常只带类型不带服务器原文（原文可能含地址）。</li>
 * </ol>
 */
public final class SmtpPasswordResetMailGateway implements PasswordResetMailGateway {

    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordResetMailGateway.class);

    /** 收件人语法（严格：无空白、无控制字符、单个 @）。 */
    private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]{1,64}"
            + "@[A-Za-z0-9-]{1,63}(\\.[A-Za-z0-9-]{1,63})+$");

    private static final DateTimeFormatter EXPIRY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    private final Session session;
    private final InternetAddress from;
    private final String username;
    private final String password;

    /**
     * @param host         SMTP 主机（必填）
     * @param port         SMTP 端口（1–65535）
     * @param username     AUTH 用户名（可空 = 匿名中继）
     * @param password     AUTH 口令（与 username 同时给或同时空）
     * @param from         发件地址（必填且必须能严格解析）
     * @param starttls     是否 STARTTLS（默认 true；required，不允许降级）
     * @param ssl          是否隐式 TLS（SMTPS，通常 465）
     * @param timeout      连接/读/写超时
     */
    public SmtpPasswordResetMailGateway(String host, int port, String username, String password,
                                        String from, boolean starttls, boolean ssl,
                                        Duration timeout) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException(
                    "口令重置邮件通道缺少 SMTP 主机：请设置 LUMIN_AUTH_RESET_MAIL_HOST");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("口令重置邮件通道端口非法：" + port);
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "口令重置邮件通道缺少发件地址：请设置 LUMIN_AUTH_RESET_MAIL_FROM");
        }
        if (starttls && ssl) {
            throw new IllegalStateException("STARTTLS 与 SMTPS 只能选一个（不能同时开启）");
        }
        boolean hasUser = username != null && !username.isBlank();
        boolean hasPassword = password != null && !password.isBlank();
        if (hasUser != hasPassword) {
            throw new IllegalStateException("SMTP 用户名与口令必须同时提供或同时留空");
        }
        if (hasPassword && !starttls && !ssl) {
            throw new IllegalStateException(
                    "配置了 SMTP 口令但未开启 STARTTLS/SMTPS：禁止在明文连接上发送凭据");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException("SMTP 超时必须为正数");
        }
        try {
            this.from = new InternetAddress(from, true);
        } catch (jakarta.mail.internet.AddressException e) {
            throw new IllegalStateException("发件地址非法（LUMIN_AUTH_RESET_MAIL_FROM）", e);
        }
        this.username = hasUser ? username : null;
        this.password = hasPassword ? password : null;

        Properties props = new Properties();
        props.setProperty("mail.smtp.host", host.trim());
        props.setProperty("mail.smtp.port", Integer.toString(port));
        props.setProperty("mail.smtp.auth", Boolean.toString(hasUser));
        props.setProperty("mail.smtp.starttls.enable", Boolean.toString(starttls));
        // required：服务器不支持 STARTTLS 就失败，绝不悄悄退回明文
        props.setProperty("mail.smtp.starttls.required", Boolean.toString(starttls));
        props.setProperty("mail.smtp.ssl.enable", Boolean.toString(ssl));
        props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        int millis = (int) timeout.toMillis();
        props.setProperty("mail.smtp.connectiontimeout", Integer.toString(millis));
        props.setProperty("mail.smtp.timeout", Integer.toString(millis));
        props.setProperty("mail.smtp.writetimeout", Integer.toString(millis));
        this.session = Session.getInstance(props);
    }

    @Override
    public void send(String recipient, String code, LocalDateTime expiresAt) {
        if (recipient == null || !ADDRESS.matcher(recipient).matches()) {
            // 不把地址写进异常/日志（地址本身是个人信息）
            throw new MailDeliveryUnavailableException("收件人地址非法，拒绝投递");
        }
        if (code == null || code.isBlank()) {
            throw new MailDeliveryUnavailableException("重置码为空，拒绝投递");
        }
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(from);
            message.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse(recipient, true));
            message.setSubject("启明书院 · 口令重置验证码", "UTF-8");
            message.setText(body(code, expiresAt), "UTF-8");
            if (username == null) {
                Transport.send(message);
            } else {
                Transport.send(message, username, password);
            }
            log.info("口令重置邮件已投递 event=password.reset.mail result=sent");
        } catch (MessagingException e) {
            // 只记类型：服务器原文可能回显收件人与诊断信息
            log.error("口令重置邮件投递失败 event=password.reset.mail result=failed reason={}",
                    e.getClass().getSimpleName());
            throw new MailDeliveryUnavailableException(
                    "SMTP 投递失败（" + e.getClass().getSimpleName() + "）");
        }
    }

    private static String body(String code, LocalDateTime expiresAt) {
        String expiry = expiresAt == null ? "" : expiresAt.toInstant(ZoneOffset.UTC)
                .atZone(ZoneOffset.UTC).format(EXPIRY);
        return """
                你好：

                你的口令重置验证码是：

                    %s

                该验证码 30 分钟内有效，且只能使用一次。%s

                如果这不是你本人的操作，请忽略本邮件——你的口令不会被修改。
                启明书院
                """.formatted(code, expiry.isEmpty() ? "" : "失效时间：" + expiry + "。");
    }
}
