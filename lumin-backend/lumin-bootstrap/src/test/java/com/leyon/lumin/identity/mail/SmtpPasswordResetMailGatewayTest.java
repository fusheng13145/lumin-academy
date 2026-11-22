package com.leyon.lumin.identity.mail;

import com.leyon.lumin.identity.service.PasswordResetMailGateway;
import com.leyon.lumin.support.mail.SmtpSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 SMTP 通道测试：用最小 SMTP 收件端（{@link SmtpSink}）走真协议收发，不 mock。
 *
 * <p>验证四件事：码真的投到收件箱、头注入被拒、带口令不加密拒绝启动、服务器不可达明确失败。
 * 报文按 Content-Transfer-Encoding 解码后断言，避免"只有 7 位 ASCII 才算投递成功"的假绿。
 */
class SmtpPasswordResetMailGatewayTest {

    private static final String FROM = "no-reply@qiming.example";
    private static final String CODE = "ABCDE-FGHIJ";

    private static SmtpSink sink;

    @BeforeAll
    static void startSink() throws Exception {
        sink = new SmtpSink();
    }

    @AfterAll
    static void stopSink() {
        sink.close();
    }

    @BeforeEach
    void clearMailbox() {
        sink.messages().clear();
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private SmtpPasswordResetMailGateway gateway(int port) {
        return new SmtpPasswordResetMailGateway("127.0.0.1", port, null, null, FROM,
                false, false, Duration.ofSeconds(3));
    }

    @Test
    void deliveredMailCarriesCodeAndRecipient() throws Exception {
        gateway(sink.port()).send("reader2026@example.com", CODE,
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30));

        String raw = sink.awaitMessage(1, Duration.ofSeconds(2));
        assertThat(SmtpSink.header(raw, "To")).isEqualTo("reader2026@example.com");
        String body = SmtpSink.decodedBody(raw);
        assertThat(body).contains(CODE).contains("30 分钟").contains("只能使用一次");
        // 正文不得出现登录名/收件人以外的身份信息拼接
        assertThat(body).doesNotContain("reader2026@example.com");
        // 明文码只在正文出现一次（不在其它头里重复）
        assertThat(raw).doesNotContain("Subject: " + CODE);
    }

    @Test
    void headerInjectionInRecipientIsRejectedBeforeSmtp() throws Exception {
        SmtpPasswordResetMailGateway gateway = gateway(sink.port());
        assertThatThrownBy(() -> gateway.send("victim@example.com\r\nBcc: evil@example.com",
                CODE, LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(PasswordResetMailGateway.MailDeliveryUnavailableException.class);
        assertThatThrownBy(() -> gateway.send("not-an-address", CODE,
                LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(PasswordResetMailGateway.MailDeliveryUnavailableException.class);
        assertThat(sink.messages()).isEmpty();
    }

    @Test
    void credentialsWithoutEncryptionRefuseToStart() {
        assertThatThrownBy(() -> new SmtpPasswordResetMailGateway("127.0.0.1", 587,
                "mailer", "secret", FROM, false, false, Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("明文");
    }

    @Test
    void missingHostOrFromRefuseToStart() throws Exception {
        int port = closedPort();
        assertThatThrownBy(() -> new SmtpPasswordResetMailGateway("  ", port, null, null, FROM,
                false, false, Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP 主机");
        assertThatThrownBy(() -> new SmtpPasswordResetMailGateway("127.0.0.1", port, null, null,
                "not an address", false, false, Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("发件地址");
    }

    @Test
    void unreachableServerFailsExplicitly() throws Exception {
        int closedPort = closedPort();
        SmtpPasswordResetMailGateway gateway = new SmtpPasswordResetMailGateway(
                "127.0.0.1", closedPort, null, null, FROM, false, false, Duration.ofSeconds(1));
        assertThatThrownBy(() -> gateway.send("reader2026@example.com", CODE,
                LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(PasswordResetMailGateway.MailDeliveryUnavailableException.class);
    }
}
