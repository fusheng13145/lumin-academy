package com.leyon.lumin.foundation.web;

import com.leyon.lumin.foundation.contract.ApiEnvelope;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理（05 §5 错误码全集 → HTTP + 错误信封）。
 * 服务器内部错误使用 D2001 稳定文案且仅日志记录 traceId，不回显异常（05 §5 末段）；
 * 统一异常层不吞掉故障后返回 OK。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(LuminBusinessException.class)
    public ResponseEntity<ApiEnvelope> business(LuminBusinessException exception) {
        return build(exception.code());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiEnvelope> badRequest(Exception ignored) {
        return build(ErrorCode.P1001);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiEnvelope> notFound(NoResourceFoundException ignored) {
        return build(ErrorCode.B2004);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope> unexpected(Exception exception) {
        log.error("未处理异常 traceId={}", ApiEnvelope.currentTraceId(), exception);
        return build(ErrorCode.D2001);
    }

    private ResponseEntity<ApiEnvelope> build(ErrorCode code) {
        return ResponseEntity.status(code.httpStatus()).body(ApiEnvelope.error(code));
    }
}

