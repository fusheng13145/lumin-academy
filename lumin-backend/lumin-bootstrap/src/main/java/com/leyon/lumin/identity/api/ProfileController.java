package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.BusinessTime;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.identity.service.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 身份档案接入层（05 §3.1 createProfile / getMe；docs/13 UGC-002）：
 * 建档要求 Idempotency-Key；出生日期仅以密文落库；GET /me 返回 ETag（revision）。
 * 主体来自认证过滤器注入的服务端权威属性，不接受客户端自报 ID。
 */
@RestController
public class ProfileController {

    /** 业务自然日按 Asia/Shanghai 计算（与 BusinessTime 同源）。 */
    private static final Clock BUSINESS_CLOCK = Clock.system(BusinessTime.ASIA_SHANGHAI);

    private final ProfileService profileService;
    private final IdempotentExecutor idempotentExecutor;

    public ProfileController(ProfileService profileService,
                             IdempotentExecutor idempotentExecutor) {
        this.profileService = profileService;
        this.idempotentExecutor = idempotentExecutor;
    }

    /** 首次建档（05 §3.1；200 Receipt，非 201）。 */
    @PostMapping("/api/v1/profiles")
    public ResponseEntity<String> createProfile(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody ProfileCreateRequest request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, request,
                () -> new IdempotentExecutor.ApiResult(200,
                        profileService.createProfile(subject, request.nickname(),
                                request.birthDate(), request.registrationConsentProofRef(),
                                BusinessTime.localDate(BUSINESS_CLOCK),
                                LocalDateTime.now(ZoneOffset.UTC))));
    }

    /** 本人档案（05 §3.1；ETag = revision）。 */
    @GetMapping("/api/v1/me")
    public ResponseEntity<ApiEnvelope> me(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String baseRole) {
        ProfileService.MeView view = profileService.me(subject, baseRole,
                LocalDateTime.now(ZoneOffset.UTC));
        return ResponseEntity.ok()
                .eTag("\"" + view.revision() + "\"")
                .body(ApiEnvelope.ok(view));
    }

    /** 建档请求体（05 §3.1 ProfileCreate；birthDate 为 ISO-8601 date）。 */
    public record ProfileCreateRequest(String nickname, String birthDate,
                                       String registrationConsentProofRef) {
    }
}
