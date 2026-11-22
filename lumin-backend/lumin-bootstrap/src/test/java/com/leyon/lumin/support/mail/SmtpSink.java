package com.leyon.lumin.support.mail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 测试用最小 SMTP 收件端（真协议：EHLO/MAIL/RCPT/DATA/QUIT）。
 *
 * <p>为什么不用 GreenMail：它基于 {@code javax.mail}，与主代码的 {@code jakarta.mail 2.0.5}
 * 在同一个测试类路径上会撞 handler（{@code org.eclipse.angus.mail.handlers.text_plain}
 * 被 {@code javax.activation.DataContentHandler} 强转失败），读不出正文。
 * 这里只实现"收下来"所需的最小状态机，报文解码由 {@link #decodedBody(String)} 负责，
 * 不引入第二套邮件 API。
 */
public final class SmtpSink implements AutoCloseable {

    private final ServerSocket server;
    private final List<String> messages = java.util.Collections.synchronizedList(new ArrayList<>());
    private final Thread thread;

    public SmtpSink() throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        this.thread = new Thread(this::acceptLoop, "smtp-sink");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public List<String> messages() {
        return messages;
    }

    /** 等第 {@code count} 封邮件到达（投递是同步的，这里只兜住调度抖动）。 */
    public String awaitMessage(int count, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (messages) {
                if (messages.size() >= count) {
                    return messages.get(count - 1);
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("SMTP 收件端在 " + timeout + " 内没收到第 " + count + " 封邮件");
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                serve(socket);
            } catch (IOException ignored) {
                // 关闭时 accept 抛异常，正常退出
            }
        }
    }

    private void serve(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.ISO_8859_1));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), false,
                StandardCharsets.ISO_8859_1);
        reply(out, "220 localhost ESMTP lumin-test-sink");
        String line;
        while ((line = in.readLine()) != null) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("EHLO")) {
                out.print("250-localhost\r\n250 SIZE 10485760\r\n");
                out.flush();
            } else if (upper.startsWith("HELO")) {
                reply(out, "250 localhost");
            } else if (upper.startsWith("MAIL FROM") || upper.startsWith("RCPT TO")
                    || upper.startsWith("RSET") || upper.startsWith("NOOP")) {
                reply(out, "250 OK");
            } else if (upper.startsWith("DATA")) {
                reply(out, "354 End data with <CR><LF>.<CR><LF>");
                StringBuilder raw = new StringBuilder();
                String dataLine;
                while ((dataLine = in.readLine()) != null && !dataLine.equals(".")) {
                    raw.append(dataLine).append("\r\n");
                }
                messages.add(raw.toString());
                reply(out, "250 OK queued");
            } else if (upper.startsWith("QUIT")) {
                reply(out, "221 Bye");
                return;
            } else {
                reply(out, "250 OK");
            }
        }
    }

    private static void reply(PrintWriter out, String text) {
        out.print(text + "\r\n");
        out.flush();
    }

    /** 从原始报文中取出正文（按 Content-Transfer-Encoding 解码，UTF-8 还原）。 */
    public static String decodedBody(String rawMessage) {
        int split = rawMessage.indexOf("\r\n\r\n");
        String headers = split < 0 ? "" : rawMessage.substring(0, split);
        String body = split < 0 ? rawMessage : rawMessage.substring(split + 4);
        String encoding = header(headers, "Content-Transfer-Encoding");
        if (encoding != null) {
            String normalized = encoding.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("base64")) {
                body = new String(Base64.getMimeDecoder().decode(body),
                        StandardCharsets.UTF_8);
            } else if (normalized.equals("quoted-printable")) {
                body = decodeQuotedPrintable(body);
            }
        }
        return body;
    }

    /** 取某个报文头（同名多值时取第一个，忽略大小写）。 */
    public static String header(String rawHeaders, String name) {
        for (String line : rawHeaders.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static String decodeQuotedPrintable(String body) {
        StringBuilder out = new StringBuilder(body.length());
        byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '=') {
                if (i + 2 < bytes.length && bytes[i + 1] == '\r' && bytes[i + 2] == '\n') {
                    i += 2; // 软换行
                } else if (i + 2 < bytes.length) {
                    out.append((char) Integer.parseInt(
                            new String(bytes, i + 1, 2, StandardCharsets.US_ASCII), 16));
                    i += 2;
                }
            } else {
                out.append((char) bytes[i]);
            }
        }
        return new String(out.toString().getBytes(StandardCharsets.ISO_8859_1),
                StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // 关闭失败不影响测试结果
        }
    }
}
