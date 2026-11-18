package com.leyon.lumin.foundation.web;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;

/**
 * If-Match 头解析（05 §2：带引号的正整数 ETag；首次进度写入使用 "0"）。
 * 缺失 → 428/P1002；格式错 → 400/P1001。
 */
public final class IfMatch {

    private IfMatch() {
    }

    public static long requireValid(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1002);
        }
        String value = ifMatch.replace("\"", "");
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return Long.parseLong(value);
    }
}
