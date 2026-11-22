package com.leyon.lumin.identity.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * TOTP（RFC 6238，HMAC-SHA1，6 位，30 秒步长）纯函数实现。
 *
 * <p><strong>为什么是自己实现而不是引第三方库</strong>：本机 Maven Central 不可达、
 * 本地仓库无任何 TOTP 制品（{@code dev.samstevens.totp} / {@code otp-java} 均无法解析），
 * 无法在本轮引入成熟库。因此这里**不发明算法**，只按 RFC 4226/6238 的规定调用 JDK 的
 * {@code Mac("HmacSHA1")} 拼装，并用 RFC 附录的**官方测试向量**做已知答案校验
 * （见 {@code TotpTest}）。一旦可引入依赖，替换点为 {@link #code}/{@link #verify}
 * 两个方法，调用方（{@code MfaTotpService}）无需改动。
 *
 * <p>安全要点：校验容忍 ±1 个时间步（时钟漂移）；比较使用**定长逐字节常量时间**；
 * 命中时间步若不大于 {@code lastUsedStep} 则拒绝（重放保护）。
 */
public final class Totp {

    /** 验证码位数（Google Authenticator 默认 6）。 */
    public static final int DIGITS = 6;
    /** 时间步长（秒）。 */
    public static final long PERIOD_SECONDS = 30L;
    /** 种子长度（160 bit，RFC 4226 §4 推荐）。 */
    public static final int SECRET_BYTES = 20;
    /** 允许的时钟漂移（步）。 */
    private static final long DRIFT_STEPS = 1L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** 生成新种子（调用方必须以密文落库，禁止明文持久化）。 */
    public static byte[] generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** 当前时间步（epoch 秒 / 30）。 */
    public static long stepAt(long epochSeconds) {
        return epochSeconds / PERIOD_SECONDS;
    }

    /** 计算指定时间步的验证码（6 位十进制，左侧补零）。 */
    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(longToBytes(step));
            // RFC 4226 §5.3 动态截断
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            long otp = binary % 1_000_000L;
            return String.format(Locale.ROOT, "%0" + DIGITS + "d", otp);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("JDK 缺少 HmacSHA1 实现", e);
        }
    }

    /**
     * 校验验证码（容忍 ±1 步）。
     *
     * @param lastUsedStep 最近一次通过校验的时间步（可为 null）；命中步 <= 该值视为重放
     * @return 命中的时间步；校验失败返回 {@code null}（含格式非法、重放）
     */
    public static Long verify(byte[] secret, String presentedCode, long epochSeconds,
                              Long lastUsedStep) {
        if (presentedCode == null || presentedCode.length() != DIGITS) {
            return null;
        }
        for (int i = 0; i < presentedCode.length(); i++) {
            if (!Character.isDigit(presentedCode.charAt(i))) {
                return null;
            }
        }
        long currentStep = stepAt(epochSeconds);
        // 逐个候选步比较（每步内部是等长常量时间比较）；命中后仍先做重放裁决再返回
        for (long step = currentStep - DRIFT_STEPS; step <= currentStep + DRIFT_STEPS; step++) {
            if (step < 0) {
                continue;
            }
            String expected = code(secret, step);
            if (constantTimeEquals(expected, presentedCode)) {
                if (lastUsedStep != null && step <= lastUsedStep) {
                    return null;
                }
                return step;
            }
        }
        return null;
    }

    /** 定长常量时间比较（等长前提下不提前返回）。 */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected.length() != actual.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ actual.charAt(i);
        }
        return diff == 0;
    }

    /** RFC 4226 §5.2：8 字节大端计数器。 */
    private static byte[] longToBytes(long value) {
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return out;
    }
}
