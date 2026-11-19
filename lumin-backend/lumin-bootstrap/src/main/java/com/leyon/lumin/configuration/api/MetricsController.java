package com.leyon.lumin.configuration.api;

import com.leyon.lumin.configuration.service.MetricsService;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可信产品与供给看板接入层（docs/13 UGC-040 一阶段）：OPERATOR/ADMIN 只读。
 * 每个读数带 frozenDefinitionVersion（口径冻结版本化）；无数据返回空集，
 * 不编数字。分端维度随客户端平台标识埋点批次。
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /** 有效阅读看板（冻结口径 v1；窗口 1–90 天，默认 7；合成流量剔除量透明呈现）。 */
    @GetMapping("/reading")
    public ApiEnvelope reading(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @RequestParam(required = false) Integer days) {
        return ApiEnvelope.ok(metricsService.reading(stubRole, days));
    }

    /** 次周留存分群（周一起算；分群人数 0 不产出——无数据不编数字）。 */
    @GetMapping("/retention")
    public ApiEnvelope retention(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole) {
        return ApiEnvelope.ok(metricsService.retention(stubRole));
    }

    /** 供给健康（提交/通过/驳回/生效下架/生效精选/真实读者与合成档案数）。 */
    @GetMapping("/supply")
    public ApiEnvelope supply(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole) {
        return ApiEnvelope.ok(metricsService.supply(stubRole));
    }
}
