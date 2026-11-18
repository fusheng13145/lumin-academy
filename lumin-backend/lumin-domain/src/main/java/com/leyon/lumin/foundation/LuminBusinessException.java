package com.leyon.lumin.foundation;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;

/**
 * 业务异常：携带 {@link ErrorCode}，由 foundation.web 的全局异常处理层
 * 统一映射为 HTTP 状态 + 错误信封（05 §5）；禁止在业务源码中手工拼错误信封。
 */
public class LuminBusinessException extends RuntimeException {

    private final ErrorCode code;

    public LuminBusinessException(ErrorCode code) {
        super(code.name() + ": " + code.message());
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
