package com.leyon.lumin.billing_reserved;

/**
 * 分币整数（04 §5.2 / 契约 MoneyMinor）。
 *
 * <p>一律 long，禁止 double/float/小数 BigDecimal；
 * 0 ≤ x ≤ 9007199254740991（2^53−1，与三端 JSON 精度一致）；
 * 负数仅 settlement.net_minor 允许且 |x| ≤ MAX。
 */
public record MoneyMinor(long minor) {

    public static final long MAX = 9007199254740991L;
    public static final MoneyMinor ZERO = new MoneyMinor(0L);

    public MoneyMinor {
        if (minor > MAX || minor < -MAX) {
            throw new IllegalArgumentException("金额超出 JS 安全整数范围：" + minor);
        }
    }

    /** 非负金额：0..MAX；负数抛 IllegalArgumentException。 */
    public static MoneyMinor ofNonNegative(long minor) {
        if (minor < 0) {
            throw new IllegalArgumentException("非负金额不得为负：" + minor);
        }
        return new MoneyMinor(minor);
    }

    /** 有符号净额：仅 settlement.net_minor 使用，|x| ≤ MAX。 */
    public static MoneyMinor ofSigned(long minor) {
        return new MoneyMinor(minor);
    }

    public static MoneyMinor zero() {
        return ZERO;
    }

    /** 构造已强制边界；本方法供调用方显式核对 JS 精度约束。 */
    public boolean withinJsSafeInteger() {
        return minor >= -MAX && minor <= MAX;
    }
}
