package com.leyon.lumin.foundation.web;

import com.leyon.lumin.foundation.contract.ApiEnvelope;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.idempotency.IdempotencyRules;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.idempotency.IdempotencyService;
import com.leyon.lumin.foundation.repository.IdempotencyRow;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/**
 * 写操作幂等执行器（03 §4.1 第 3 条 / T09）：命中未过期同键同摘要 → 原样回放
 * 存储响应；同键不同载荷（含 If-Match 差异）→ B2003；新鲜执行成功后按实际
 * HTTP 状态记录响应（202 受理语义不伪造为 200）。失败请求不记录（不存伪成功）。
 * 摘要对「规范化载荷 JSON + If-Match」计算，路径/方法进入作用域。
 */
@Component
public class IdempotentExecutor {

    /** 执行结果：status 为实际 HTTP 状态（200/202），data 进信封 data 字段。 */
    public record ApiResult(int status, Object data) {
    }

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public IdempotentExecutor(IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<String> execute(String subject, String method, String uri,
                                          String idempotencyKey, String ifMatch, Object payload,
                                          Supplier<ApiResult> action) {
        if (IdempotencyRules.validateKey(idempotencyKey) != ErrorCode.OK) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        // Jackson 3 异常为非受检：自身 DTO 序列化失败属服务器错误，交全局异常层
        String digest = IdempotencyRules.requestDigest(
                objectMapper.writeValueAsString(payload) + "\n" + ifMatch);
        IdempotencyService.Scope scope = new IdempotencyService.Scope(subject, method,
                IdempotencyRules.normalizePath(uri), idempotencyKey, digest,
                LocalDateTime.now(ZoneOffset.UTC));
        var replay = idempotencyService.findReplay(scope);
        if (replay.isPresent()) {
            IdempotencyRow stored = replay.get();
            return ResponseEntity.status(stored.responseCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(stored.responseBody());
        }
        ApiResult result = action.get();
        String body = objectMapper.writeValueAsString(ApiEnvelope.ok(result.data()));
        idempotencyService.record(scope, result.status(), body);
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}

