package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.identity.repository.RightsRequestRow;
import com.leyon.lumin.identity.service.RightsRequestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 数据主体权利请求接入层（05 §3.4：POST/GET /me/rights-requests，仅创建者
 * 本人；ERASE 受法律保全闸门；不返回公开导出地址——result_ref 加密留痕）。
 */
@RestController
@RequestMapping("/api/v1/me/rights-requests")
public class RightsRequestController {

    private final RightsRequestService service;

    public RightsRequestController(RightsRequestService service) {
        this.service = service;
    }

    /** 提交权利请求（本人自助闭环；ERASE 受法律保全闸门 → HELD 可观察）。 */
    @PostMapping
    public ApiEnvelope create(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestBody CreateRequest request) {
        if (request == null || request.kind() == null || request.kind().isBlank()) {
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.P1001);
        }
        RightsRequestRow row = service.create(subject, request.kind(), request.reason(),
                request.correctionNickname(), LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(toView(row));
    }

    /** 本人请求列表（不开放他人请求）。 */
    @GetMapping
    public ApiEnvelope list(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestParam(defaultValue = "20") int limit) {
        List<RightsRequestRow> rows = service.listMine(subject);
        return ApiEnvelope.ok(rows.stream().limit(Math.min(Math.max(limit, 1), 100))
                .map(RightsRequestController::toView).toList());
    }

    /** 本人单条（原始请求、状态、到期时间；他人 404）。 */
    @GetMapping("/{id}")
    public ApiEnvelope get(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id) {
        return ApiEnvelope.ok(toView(service.getMine(subject, id)));
    }

    /** 权利请求视图（result_ref 加密留痕不回显——导出下载随二次认证批次）。 */
    public record RightsRequestView(String id, String kind, String state, String reason,
                                    String dueAt, String holdId, boolean hasResult) {
    }

    private static RightsRequestView toView(RightsRequestRow row) {
        return new RightsRequestView(row.id(), row.kind(), row.state(), row.reason(),
                row.dueAt().toInstant(ZoneOffset.UTC).toString(), row.holdId(),
                row.resultRefCipher() != null);
    }

    /** 提交请求体（kind=ACCESS/EXPORT/CORRECT/ERASE；CORRECT 附 correctionNickname）。 */
    public record CreateRequest(String kind, String reason, String correctionNickname) {
    }
}
