package com.leyon.lumin.governance;

import com.leyon.lumin.governance.service.RestrictionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 临时限制到期扫描（03 §2.4）：分批将 ACTIVE TEMPORARY 且已过 expiresAt 的限制
 * 持久化为 EXPIRED，进入复核告警；不调用发布恢复，因此不会自动公开。
 * 测试显式关闭调度、直调服务方法保证确定性；生产配置开启。
 */
@Component
@ConditionalOnProperty(name = "lumin.restriction.expiry-scan.enabled", havingValue = "true")
public class RestrictionExpiryScheduler {

    private final RestrictionService restrictionService;

    public RestrictionExpiryScheduler(RestrictionService restrictionService) {
        this.restrictionService = restrictionService;
    }

    @Scheduled(fixedDelayString = "${lumin.restriction.expiry-scan.interval-ms:300000}")
    public void expireOnce() {
        restrictionService.expireDueTemporary(LocalDateTime.now(ZoneOffset.UTC));
    }
}
