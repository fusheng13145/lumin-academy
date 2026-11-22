package com.leyon.lumin.audit.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 审计快照字段白名单（2026-11-20 安全审查 18 项收尾）。
 *
 * <p>改前 {@code AuditAppender} 把调用方给的 Map **原样**序列化入库：任何服务只要顺手把
 * 口令、令牌、邮箱、生日、证件号塞进 before/after，就会永久写进 append-only 的审计表
 * ——审计表不能删改，泄露即永久泄露。
 *
 * <p>本类把"信任调用方"改成"只接受白名单"：
 * <ul>
 *   <li>**白名单之外的键一律丢弃**（不猜语义、不做启发式脱敏），只留一个
 *       {@code _dropped} 计数，让审查者知道"这里原本还有字段被拦下"；</li>
 *   <li>值截断到 120 字符、条目上限 12 条——审计快照只应记录状态名，不承载正文；</li>
 *   <li>白名单里的键也只允许写状态类值，敏感键名（password/token/secret/code/email
 *       /birth/cipher）即便被调用方误用也会被丢弃，因为白名单里没有它们。</li>
 * </ul>
 *
 * <p>迁移影响：现有四处调用（{@code businessState / restrictionState / holdState /
 * configState}）全部在白名单内，历史 digest 口径不变（T35 归档基线不需重算）。
 */
public final class AuditSnapshotSanitizer {

    /** 单张快照最多保留的条目数。 */
    public static final int MAX_ENTRIES = 12;
    /** 单个值最大长度（超出截断并加省略号）。 */
    public static final int MAX_VALUE_LENGTH = 120;
    /** 被丢弃字段的计数键（仅在被拦截时才出现）。 */
    public static final String DROPPED_KEY = "_dropped";

    /** 允许进入审计快照的键（全部是"状态名"语义，不含任何凭证/个人信息）。 */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "businessState", "restrictionState", "holdState", "configState",
            "publicationState", "roleGrantState", "accountState", "mfaState",
            "deliveryState", "appealState");

    private AuditSnapshotSanitizer() {
    }

    /**
     * 过滤快照。
     *
     * @return 过滤后的不可变视图；输入为空或全部被丢弃（且无计数意义）时返回 null
     */
    public static Map<String, String> sanitize(Map<String, String> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        int dropped = 0;
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            String key = entry.getKey() == null ? null : entry.getKey().trim();
            if (key == null || !ALLOWED_KEYS.contains(key) || entry.getValue() == null) {
                dropped++;
                continue;
            }
            if (out.size() >= MAX_ENTRIES) {
                dropped++;
                continue;
            }
            out.put(key, truncate(entry.getValue()));
        }
        if (dropped > 0) {
            out.put(DROPPED_KEY, String.valueOf(dropped));
        }
        return out.isEmpty() ? null : java.util.Collections.unmodifiableMap(out);
    }

    private static String truncate(String value) {
        return value.length() <= MAX_VALUE_LENGTH ? value
                : value.substring(0, MAX_VALUE_LENGTH) + "…";
    }
}
