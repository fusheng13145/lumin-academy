package com.leyon.lumin.identity;

import com.leyon.lumin.identity.service.AccountClosureService;
import com.leyon.lumin.identity.service.RightsRequestService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 匿名化推进调度器（03 §8 / docs/13 UGC-005 收口；T36）：SYSTEM 定时扫描——
 * ① 注销满 6 个月且无 ACTIVE 保全 → 内部关联匿名化（promoteDue）；
 * ② HELD 的 ERASE 权利请求在保全解除后续跑（retryHeld）。
 * 仅当 `lumin.closure.promotion.enabled=true` 时创建——集成测试显式关闭调度、
 * 直调服务方法保证确定性；生产配置开启。审计事实不删（每次推进留审计）。
 */
@Component
@ConditionalOnProperty(name = "lumin.closure.promotion.enabled", havingValue = "true")
public class AnonymizationPromotionScheduler {

    private final AccountClosureService closureService;
    private final RightsRequestService rightsRequestService;

    public AnonymizationPromotionScheduler(AccountClosureService closureService,
                                           RightsRequestService rightsRequestService) {
        this.closureService = closureService;
        this.rightsRequestService = rightsRequestService;
    }

    /** 推进扫描：注销到期匿名化 + HELD 权利请求续跑。 */
    @Scheduled(fixedDelayString = "${lumin.closure.promotion.interval-ms:3600000}")
    public void promoteOnce() {
        closureService.promoteDue(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        rightsRequestService.retryHeld(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
    }
}
