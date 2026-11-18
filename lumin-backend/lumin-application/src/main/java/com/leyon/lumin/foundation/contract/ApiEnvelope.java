package com.leyon.lumin.foundation.contract;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * 统一响应信封（05 §2 / §4.4）：code、message、data、traceId 全部必填；
 * 成功 code=OK；错误信封 data=null；信封拒绝未知字段由 Jackson 配置保证。
 * traceId 由 Web 层 TraceIdFilter 写入 MDC，缺失时兜底生成。
 */
public record ApiEnvelope(String code, String message, Object data, String traceId) {

    /** 与 bootstrap TraceIdFilter 的 MDC 键保持一致，避免应用层依赖 Servlet API。 */
    private static final String TRACE_MDC_KEY = "lumin.traceId";
    private static final String OK_MESSAGE = "成功/已受理，语义由操作决定";

    public static ApiEnvelope ok(Object data) {
        return new ApiEnvelope(ErrorCode.OK.name(), OK_MESSAGE, data, currentTraceId());
    }

    /** 已受理语义（202）：文案仍为信封稳定文案，语义由 HTTP 状态码承载（05 §5）。 */
    public static ApiEnvelope accepted(Object data) {
        return new ApiEnvelope(ErrorCode.OK.name(), OK_MESSAGE, data, currentTraceId());
    }

    public static ApiEnvelope error(ErrorCode code) {
        return new ApiEnvelope(code.name(), code.message(), null, currentTraceId());
    }

    public static String currentTraceId() {
        String traceId = MDC.get(TRACE_MDC_KEY);
        return traceId != null && !traceId.isBlank() ? traceId : UUID.randomUUID().toString();
    }
}

