package com.leyon.lumin.authoring.domain;

import com.leyon.lumin.catalog.domain.TargetRef;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.TargetType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 提交材料快照与 materialSha256（03 §4.1 第 8 段 / 04 §5.2 末段）。
 *
 * <p>固定对象键：targetType、workId、chapterId、title、synopsis、categoryId、tags、
 * coverAssetSha256、bodySha256；不适用键取 null；tags 保持用户顺序；UUID 为小写字符串。
 * 递归按键的 Unicode 码点排序（本快照为扁平结构、键集固定），UTF-8 无 BOM 紧凑 JSON，
 * 换行统一 LF，字符串按 JSON 标准转义，不额外去空白或做 Unicode 合并。
 *
 * <p>materialSha256 是对上述字节串的 SHA-256，绑定不可变完整审核材料快照，
 * 不是仅正文哈希（05 §4.3）；快照内容从冻结版本读取并与版本一并持久化，
 * Agent 必须使用同一快照（INV-07）。WORK 无正文时仍有完整材料摘要，
 * bodySha256 只用于 CHAPTER 对象字节完整性。
 */
public record MaterialSnapshot(TargetType targetType, UUID workId, UUID chapterId, String title,
                               String synopsis, UUID categoryId, List<String> tags,
                               String coverAssetSha256, String bodySha256) {

    private static final String SHA256_HEX_PATTERN = "[a-f0-9]{64}";

    /** 快照结构校验：返回 {@link ErrorCode#OK} 表示合法，否则为拒绝错误码（400/P1001）。 */
    public ErrorCode validate() {
        TargetRef ref = new TargetRef(targetType, workId, chapterId);
        if (ref.validate() != ErrorCode.OK) {
            return ErrorCode.P1001;
        }
        if (title == null || title.isBlank()
                || title.codePointCount(0, title.length()) > 100) {
            return ErrorCode.P1001;
        }
        if (tags == null) {
            return ErrorCode.P1001;
        }
        if (tags.size() > 5) {
            return ErrorCode.P1001;
        }
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                return ErrorCode.P1001;
            }
        }
        switch (targetType) {
            case WORK -> {
                if (synopsis == null || synopsis.isBlank()
                        || synopsis.codePointCount(0, synopsis.length()) > 2000) {
                    return ErrorCode.P1001;
                }
                if (categoryId == null) {
                    return ErrorCode.P1001;
                }
                if (bodySha256 != null) {
                    return ErrorCode.P1001;
                }
            }
            case CHAPTER -> {
                // 05 §4.1：CHAPTER 禁止 synopsis/categoryId；封面属作品级，键取 null
                if (synopsis != null || categoryId != null || coverAssetSha256 != null) {
                    return ErrorCode.P1001;
                }
                if (!tags.isEmpty()) {
                    return ErrorCode.P1001;
                }
                if (bodySha256 == null || !bodySha256.matches(SHA256_HEX_PATTERN)) {
                    return ErrorCode.P1001;
                }
            }
            default -> {
                return ErrorCode.P1001;
            }
        }
        if (coverAssetSha256 != null && !coverAssetSha256.matches(SHA256_HEX_PATTERN)) {
            return ErrorCode.P1001;
        }
        return ErrorCode.OK;
    }

    /** 规范化 JSON 字节串：键按 Unicode 码点排序、紧凑、UTF-8 无 BOM、字符串标准转义。 */
    public byte[] canonicalBytes() {
        StringBuilder json = new StringBuilder(256);
        // 键按 Unicode 码点排序（固定 ASCII 键集）：
        // bodySha256 < categoryId < chapterId < coverAssetSha256 < synopsis < tags
        // < targetType < title < workId
        json.append('{');
        writeStringField(json, "bodySha256", bodySha256);
        json.append(',');
        writeStringField(json, "categoryId", categoryId == null ? null : categoryId.toString());
        json.append(',');
        writeStringField(json, "chapterId", chapterId == null ? null : chapterId.toString());
        json.append(',');
        writeStringField(json, "coverAssetSha256", coverAssetSha256);
        json.append(',');
        writeStringField(json, "synopsis", synopsis);
        json.append(',');
        writeStringArrayField(json, "tags", tags);
        json.append(',');
        writeStringField(json, "targetType", targetType == null ? null : targetType.name());
        json.append(',');
        writeStringField(json, "title", title);
        json.append(',');
        writeStringField(json, "workId", workId == null ? null : workId.toString());
        json.append('}');
        // UTF-8 无 BOM
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 材料摘要：对规范化字节串计算 SHA-256，小写十六进制（与 body 正文摘要相互独立）。 */
    public String materialSha256() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    private static void writeStringField(StringBuilder json, String key, String value) {
        json.append('"').append(escape(key)).append("\":");
        if (value == null) {
            json.append("null");
        } else {
            json.append('"').append(escape(value)).append('"');
        }
    }

    private static void writeStringArrayField(StringBuilder json, String key, List<String> values) {
        json.append('"').append(escape(key)).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escape(values.get(i))).append('"');
        }
        json.append(']');
    }

    /** JSON 标准转义：仅 ", \ 与 <0x20 控制字符；不转义非 ASCII、不做 Unicode 合并。 */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
