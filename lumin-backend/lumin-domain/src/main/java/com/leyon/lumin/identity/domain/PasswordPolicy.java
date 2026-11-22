package com.leyon.lumin.identity.domain;

import java.util.Locale;
import java.util.Set;

/**
 * 口令强度策略（2026-11-20 安全审查 05 项落地；纯函数、无 IO、无时钟）。
 *
 * <p>规则（与 OWASP ASVS 5.1「口令强度」对齐的工程最小集）：
 * <ol>
 *   <li>长度 10–72（72 为 BCrypt 输入上限，超出部分会被静默截断，必须显式拒绝）；</li>
 *   <li>四类字符（小写/大写/数字/其他）至少命中 3 类；</li>
 *   <li>不得包含登录名（长度 ≥4 时，忽略大小写）——阻止 "alice2026" 这类组合；</li>
 *   <li>不得是常见弱口令（本地内置清单，**不调外部服务**，避免把口令送去第三方）。</li>
 * </ol>
 * 本类只做"是否合格"的裁决；错误码映射由应用层负责（统一 400/P1001）。
 */
public final class PasswordPolicy {

    /** 口令最短长度（OWASP 推荐最小 8，本项目从严取 10）。 */
    public static final int MIN_LENGTH = 10;
    /** 口令最长长度（BCrypt 有效输入上限）。 */
    public static final int MAX_LENGTH = 72;
    /** 登录名参与"包含判断"的最小长度（过短会误伤 2–3 字符的常见词）。 */
    private static final int LOGIN_ID_MIN_FOR_CONTAINS = 4;

    /** 常见弱口令（小写比对；覆盖各语言站点泄露榜高频项与项目相关词）。 */
    private static final Set<String> COMMON_WEAK = Set.of(
            "123456", "123456789", "12345678", "1234567890", "12345",
            "password", "password1", "password123", "passw0rd", "p@ssw0rd",
            "qwerty", "qwerty123", "qwertyuiop", "1qaz2wsx", "1q2w3e4r",
            "abc123", "abcd1234", "abcdefgh", "iloveyou", "admin",
            "admin123", "administrator", "root1234", "letmein", "welcome",
            "welcome1", "monkey123", "dragon123", "sunshine", "football",
            "woaini1314", "5201314520", "a123456789", "aa123456", "test1234",
            "lumin123", "qiming123", "shuyuan123");

    private PasswordPolicy() {
    }

    /** 违规类型（应用层只用于日志，对外统一 400/P1001，避免泄露策略细节）。 */
    public enum Violation {
        /** 过短或为空。 */
        TOO_SHORT,
        /** 超过 BCrypt 输入上限。 */
        TOO_LONG,
        /** 字符类不足 3 类。 */
        TOO_FEW_CLASSES,
        /** 包含登录名。 */
        CONTAINS_LOGIN_ID,
        /** 命中常见弱口令。 */
        COMMON_PASSWORD
    }

    /**
     * 校验口令。
     *
     * @param password 明文口令（仅内存态，调用方不得持久化/打印）
     * @param loginId  登录名（用于"包含登录名"判断；为 null 时跳过该规则）
     * @return 违规类型；{@code null} 表示通过
     */
    public static Violation check(String password, String loginId) {
        if (password == null || password.length() < MIN_LENGTH) {
            return Violation.TOO_SHORT;
        }
        if (password.length() > MAX_LENGTH) {
            return Violation.TOO_LONG;
        }
        int classes = 0;
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean other = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (c >= 'a' && c <= 'z') {
                lower = true;
            } else if (c >= 'A' && c <= 'Z') {
                upper = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            } else {
                other = true;
            }
        }
        classes = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
        if (classes < 3) {
            return Violation.TOO_FEW_CLASSES;
        }
        String normalized = password.toLowerCase(Locale.ROOT);
        if (COMMON_WEAK.contains(normalized)) {
            return Violation.COMMON_PASSWORD;
        }
        if (loginId != null && loginId.length() >= LOGIN_ID_MIN_FOR_CONTAINS
                && normalized.contains(loginId.toLowerCase(Locale.ROOT))) {
            return Violation.CONTAINS_LOGIN_ID;
        }
        return null;
    }
}
