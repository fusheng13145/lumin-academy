package com.leyon.lumin.identity.domain;

/**
 * RFC 4648 Base32 编解码（无填充，字母表 A–Z2–7）。
 *
 * <p>仅用于 TOTP 种子的展示/录入（{@code otpauth://} URI 与手动输入），
 * 不承担任何加密职责——种子本身的机密性由 {@code FieldCipher}（AES-256-GCM）保障。
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] LOOKUP = new int[128];

    static {
        for (int i = 0; i < LOOKUP.length; i++) {
            LOOKUP[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length(); i++) {
            LOOKUP[ALPHABET.charAt(i)] = i;
        }
    }

    private Base32() {
    }

    /** 编码（无 '=' 填充；与 Authenticator App 的口径一致）。 */
    public static String encode(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    /** 解码（大小写不敏感、忽略 '=' 与空格/连字符；非法字符抛 IllegalArgumentException）。 */
    public static byte[] decode(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Base32 输入为空");
        }
        StringBuilder cleaned = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '=' || c == ' ' || c == '-') {
                continue;
            }
            cleaned.append(Character.toUpperCase(c));
        }
        byte[] out = new byte[cleaned.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            int value = c < 128 ? LOOKUP[c] : -1;
            if (value < 0) {
                throw new IllegalArgumentException("Base32 含非法字符：" + c);
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return java.util.Arrays.copyOf(out, index);
    }
}
