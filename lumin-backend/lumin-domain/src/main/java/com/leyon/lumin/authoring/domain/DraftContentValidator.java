package com.leyon.lumin.authoring.domain;

import com.leyon.lumin.catalog.domain.TargetRef;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;

import java.util.List;
import java.util.UUID;

/**
 * 草稿载荷校验（03 §4.1 第 1 条 / 04 §4 draft 表 / 05 §4.1）。
 *
 * <p>WORK 载荷：workId/title/synopsis/categoryId/tags，chapterId 与 body 禁止；
 * CHAPTER 载荷：workId/chapterId/title/body，synopsis/categoryId 禁止。
 * 正文纯文本 1–100000 字符、UTF-8 ≤400KiB；tags ≤5 个非空字符串；
 * 标题 1–100 字符、简介 ≤2000 字符。字符串按 Unicode 码点计数。
 */
public final class DraftContentValidator {

    public static final int TITLE_MIN_CODEPOINTS = 1;
    public static final int TITLE_MAX_CODEPOINTS = 100;
    public static final int SYNOPSIS_MAX_CODEPOINTS = 2000;
    public static final int TAGS_MAX_COUNT = 5;
    public static final int BODY_MIN_CODEPOINTS = 1;
    public static final int BODY_MAX_CODEPOINTS = 100000;
    /** 400KiB = 409600 字节（05 §2 请求体约束）。 */
    public static final int BODY_MAX_UTF8_BYTES = 409600;
    /** 整请求 ≤512KiB = 524288 字节（05 §2）。 */
    public static final int REQUEST_MAX_BYTES = 524288;

    private DraftContentValidator() {
    }

    /**
     * 校验草稿载荷：返回 {@link ErrorCode#OK} 表示合法，否则为拒绝错误码（400/P1001）。
     * 归属/年龄/版权/配额等业务前提由服务层核验（03 §4.1 第 2 条），不在此范围。
     */
    public static ErrorCode validate(TargetRef target, String title, String synopsis,
                                     UUID categoryId, List<String> tags, String bodyText) {
        if (target == null || target.validate() != ErrorCode.OK) {
            return ErrorCode.P1001;
        }
        if (title == null || title.isBlank()
                || title.codePointCount(0, title.length()) > TITLE_MAX_CODEPOINTS) {
            return ErrorCode.P1001;
        }
        if (synopsis != null
                && synopsis.codePointCount(0, synopsis.length()) > SYNOPSIS_MAX_CODEPOINTS) {
            return ErrorCode.P1001;
        }
        if (tags != null && tags.size() > TAGS_MAX_COUNT) {
            return ErrorCode.P1001;
        }
        if (tags != null) {
            for (String tag : tags) {
                if (tag == null || tag.isBlank()) {
                    return ErrorCode.P1001;
                }
            }
        }
        switch (target.targetType()) {
            case WORK -> {
                // WORK 必有 synopsis/category_id 且 body_text 为空（04 §4 draft 表）
                if (synopsis == null || synopsis.isBlank() || categoryId == null) {
                    return ErrorCode.P1001;
                }
                if (bodyText != null && !bodyText.isBlank()) {
                    return ErrorCode.P1001;
                }
            }
            case CHAPTER -> {
                // CHAPTER 必有正文；synopsis/categoryId 禁止（05 §4.1）
                if (synopsis != null || categoryId != null) {
                    return ErrorCode.P1001;
                }
                if (bodyText == null) {
                    return ErrorCode.P1001;
                }
                if (bodyText.codePointCount(0, bodyText.length()) < BODY_MIN_CODEPOINTS
                        || bodyText.codePointCount(0, bodyText.length()) > BODY_MAX_CODEPOINTS) {
                    return ErrorCode.P1001;
                }
                if (bodyText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > BODY_MAX_UTF8_BYTES) {
                    return ErrorCode.P1001;
                }
            }
            default -> {
                return ErrorCode.P1001;
            }
        }
        return ErrorCode.OK;
    }
}
