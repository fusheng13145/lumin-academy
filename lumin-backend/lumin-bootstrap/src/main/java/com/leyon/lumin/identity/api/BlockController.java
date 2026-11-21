package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.identity.repository.UserBlockMapper;
import com.leyon.lumin.identity.service.BlockService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 互动安全接入层（docs/13 UGC-043 一阶段）：拉黑/解除/列表（个人安全
 * 自服务——任何已认证主体）。列表仅本人可见（不默认公开关系图）。
 * 执行面（评论/关注/通知过滤）经 foundation/port.InteractionGatePort
 * 随 UGC-042/044 接线。
 */
@RestController
@RequestMapping("/api/v1/me/blocks")
public class BlockController {

    private final BlockService blockService;

    public BlockController(BlockService blockService) {
        this.blockService = blockService;
    }

    /** 拉黑（目标须存在；不能拉黑自己 400；重复 409；审计留痕）。 */
    @PostMapping
    public ApiEnvelope block(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestBody BlockRequest request) {
        blockService.block(subject, request.subjectId(), request.reason(),
                LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("subjectId", request.subjectId(), "blocked", true));
    }

    /** 解除拉黑（幂等；跨账号隔离）。 */
    @DeleteMapping("/{subjectId}")
    public ApiEnvelope unblock(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String subjectId) {
        blockService.unblock(subject, subjectId, LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("subjectId", subjectId, "blocked", false));
    }

    /** 拉黑列表（仅本人可见；被拉黑人昵称截断展示）。 */
    @GetMapping
    public ApiEnvelope list(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject) {
        List<UserBlockMapper.BlockListItem> items = blockService.listMine(subject);
        return ApiEnvelope.ok(items.stream()
                .map(item -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("subjectId", item.blockedId());
                    view.put("nickname", item.blockedNickname());
                    view.put("reason", item.reason() == null ? "" : item.reason());
                    view.put("blockedAt", item.createdAt().toInstant(ZoneOffset.UTC).toString());
                    return view;
                })
                .toList());
    }

    /** 拉黑请求体（目标主体 UUID；原因可选 ≤100 字符）。 */
    public record BlockRequest(String subjectId, String reason) {
    }
}
