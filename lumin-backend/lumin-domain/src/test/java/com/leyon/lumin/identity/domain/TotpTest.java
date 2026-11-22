package com.leyon.lumin.identity.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TOTP / Base32 已知答案校验（2026-11-20 安全审查 05 项）。
 *
 * <p>本实现是"按 RFC 拼装 JDK 原语"，因此正确性必须由 **RFC 官方测试向量**证明，
 * 而不是由自己写的断言自证：
 * <ul>
 *   <li>RFC 4226 附录 D：计数器 0–9 的 HMAC-SHA1 HOTP 结果；</li>
 *   <li>RFC 6238 附录 B：SHA1 时间点的 8 位 TOTP（本实现 6 位，取末 6 位比对）。</li>
 * </ul>
 * 若将来换成成熟库（见 {@link Totp} 类注释的替换点），本测试可原样保留作为回归基线。
 */
class TotpTest {

    /** RFC 4226/6238 测试向量共用的种子（ASCII "12345678901234567890"，20 字节）。 */
    private static final byte[] RFC_SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void hotpMatchesRfc4226AppendixD() {
        // 计数器 0..9 的官方结果（RFC 4226 Appendix D 表格逐行）
        String[] expected = {"755224", "287082", "359152", "969429", "338314",
                "254676", "287922", "162583", "399871", "520489"};
        for (int counter = 0; counter < expected.length; counter++) {
            assertEquals(expected[counter], Totp.code(RFC_SECRET, counter),
                    "RFC 4226 附录 D 计数器 " + counter + " 不符");
        }
    }

    @Test
    void totpMatchesRfc6238AppendixB() {
        // RFC 6238 Appendix B 的 SHA1 行：时间点 → 8 位结果；本实现 6 位 → 取末 6 位
        Object[][] vectors = {
                {59L, "94287082"},
                {1111111109L, "07081804"},
                {1111111111L, "14050471"},
                {1234567890L, "89005924"},
                {2000000000L, "69279037"},
                {20000000000L, "65353130"}
        };
        for (Object[] vector : vectors) {
            long epochSeconds = (Long) vector[0];
            String eightDigits = (String) vector[1];
            assertEquals(eightDigits.substring(2),
                    Totp.code(RFC_SECRET, Totp.stepAt(epochSeconds)),
                    "RFC 6238 时间点 " + epochSeconds + " 不符");
        }
    }

    @Test
    void verifyAcceptsDriftStepAndRejectsReplay() {
        // 时间点 59 → 时间步 1
        String codeAtStep1 = Totp.code(RFC_SECRET, 1);
        // 当前步命中
        assertEquals(Long.valueOf(1L), Totp.verify(RFC_SECRET, codeAtStep1, 59L, null));
        // 下一步仍接受上一步（±1 步漂移）：时间点 89 → 步 2，步 1 的码在窗口内
        assertEquals(Long.valueOf(1L), Totp.verify(RFC_SECRET, codeAtStep1, 89L, 0L));
        // 已使用过该步 → 判为重放
        assertNull(Totp.verify(RFC_SECRET, codeAtStep1, 89L, 1L));
        // 超出漂移窗口（时间点 149 → 步 4）
        assertNull(Totp.verify(RFC_SECRET, codeAtStep1, 149L, null));
    }

    @Test
    void verifyRejectsMalformedCodes() {
        assertNull(Totp.verify(RFC_SECRET, null, 59L, null));
        assertNull(Totp.verify(RFC_SECRET, "12345", 59L, null));
        assertNull(Totp.verify(RFC_SECRET, "abcdef", 59L, null));
        assertNull(Totp.verify(RFC_SECRET, "12345a", 59L, null));
        // 恢复码（10 位字母数字）不得被当作 TOTP 命中
        assertNull(Totp.verify(RFC_SECRET, "ABCDEFGHIJ", 59L, null));
    }

    @Test
    void generatedSecretHasRfcLengthAndVaries() {
        byte[] first = Totp.generateSecret();
        byte[] second = Totp.generateSecret();
        assertEquals(Totp.SECRET_BYTES, first.length);
        assertEquals(Totp.SECRET_BYTES, second.length);
        assertTrue(!java.util.Arrays.equals(first, second), "两次生成的种子不得相同");
    }

    @Test
    void base32DecodesRfc4648VectorAndRoundTrips() {
        // RFC 4648 §10：BASE32("foobar") = "MZXW6YTBOI======"（本实现无填充）
        assertEquals("foobar", ascii(Base32.decode("MZXW6YTBOI")));
        // 大小写 / 分隔符 / 填充容错（用户手输 otpauth 种子时常见）
        assertEquals("foobar", ascii(Base32.decode("mzxw6ytboi")));
        assertEquals("foobar", ascii(Base32.decode("MZXW 6YTB-OI======")));
        // 编解码往返
        for (int i = 0; i < 5; i++) {
            byte[] secret = Totp.generateSecret();
            String encoded = Base32.encode(secret);
            assertTrue(java.util.Arrays.equals(secret, Base32.decode(encoded)),
                    "Base32 往返不一致：" + encoded);
        }
        // 非法字符必须显式失败（不静默当成空）
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Base32.decode("MZXW6YTBO1"));
    }

    private static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
