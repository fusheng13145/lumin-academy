package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.identity.repository.AuthIpThrottleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * 免认证入口的按 IP 配额（2026-11-20 安全审查 19 项）。
 *
 * <p>保护对象是**没有凭据可锁**的入口：口令登录、注册、二次验证挑战。三者都能被
 * 无限次尝试——账号级失败锁定（V20）只挡得住"盯住一个账号"的攻击，
 * 挡不住"同一 IP 横扫一片账号"（撞库最常见的形态）。
 *
 * <p>口径：
 * <ul>
 *   <li>固定 1 分钟窗口，窗口内**所有**尝试都计数（不只失败，避免"成功后清零"被绕过），
 *       超过配额一律 429/A2003；</li>
 *   <li>计数落库（{@code auth_ip_throttle}），多副本一致、重启不丢；</li>
 *   <li>只存 IP 的加盐 SHA-256（salt 取 {@code lumin.crypto.key}），不存原始地址；</li>
 *   <li>配额按"全部认证入口合计"计，不给攻击者换端点续命的机会。</li>
 * </ul>
 *
 * <p><strong>被拒的那次不落库</strong>：超限时计数写入随本方法事务一起回滚，
 * 库里停留在"恰好达上限"的值上——效果等价（上限内一律拒绝），但不会让攻击者
 * 把计数推成天文数字。</p>
 *
 * <p><strong>已知边界（如实登记）</strong>：取的是 {@code request.getRemoteAddr()}，
 * 反向代理后需由网关写入真实客户端地址（未信任 {@code X-Forwarded-For}，因为该头可伪造，
 * 信任它等于把配额交给攻击者）；NAT/校园网出口会共享配额，故默认 20 次/分钟
 * （{@code lumin.auth.ip-limit.attempts-per-minute} 可调）。
 */
@Service
public class AuthIpThrottleService {

    private static final Logger log = LoggerFactory.getLogger(AuthIpThrottleService.class);

    /** 计数窗口（分钟）。 */
    private static final int WINDOW_MINUTES = 1;
    /** 陈旧窗口保留时长（超过即清理）。 */
    private static final int RETENTION_HOURS = 2;

    private final AuthIpThrottleMapper mapper;
    private final int attemptsPerMinute;
    private final String salt;
    private final boolean enabled;

    public AuthIpThrottleService(
            AuthIpThrottleMapper mapper,
            @Value("${lumin.auth.ip-limit.attempts-per-minute:20}") int attemptsPerMinute,
            @Value("${lumin.auth.ip-limit.enabled:true}") boolean enabled,
            @Value("${lumin.crypto.key:}") String salt) {
        this.mapper = mapper;
        this.attemptsPerMinute = attemptsPerMinute;
        this.enabled = enabled;
        this.salt = salt == null ? "" : salt;
    }

    /**
     * 计入一次尝试；超出配额抛 429/A2003。
     *
     * <p>调用点必须在**任何**凭据校验之前：先计数后校验，否则攻击者可以用
     * 失败响应时间差判断"是否已进入配额"。
     */
    @Transactional
    public void acquire(String clientIp, LocalDateTime now) {
        if (!enabled) {
            return;
        }
        LocalDateTime windowStart = now.truncatedTo(ChronoUnit.MINUTES);
        String ipHash = hash(clientIp);
        mapper.increment(ipHash, windowStart, now);
        Integer attempts = mapper.attempts(ipHash, windowStart);
        if (attempts != null && attempts > attemptsPerMinute) {
            // 只记窗口与次数，不记 IP / 摘要（日志不引入新的个人数据面）
            log.warn("认证入口配额超限 event=auth.ip_quota result=rejected attempts={} limit={} "
                    + "window={}", attempts, attemptsPerMinute, windowStart);
            throw new LuminBusinessException(ErrorCode.A2003);
        }
        if (attempts != null && attempts == 1) {
            // 新窗口首次计数：顺手清理陈旧窗口，避免表无限增长（每 IP 每分钟至多一次）
            mapper.deleteBefore(now.minusHours(RETENTION_HOURS));
        }
    }

    /** 加盐摘要（盐与 FieldCipher 同源；缺盐时退化为无盐哈希，仅影响不可枚举性）。 */
    private String hash(String clientIp) {
        String value = (clientIp == null ? "unknown" : clientIp) + "|" + salt;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }
}
