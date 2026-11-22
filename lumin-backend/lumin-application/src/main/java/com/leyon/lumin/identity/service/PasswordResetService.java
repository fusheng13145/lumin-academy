package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.identity.domain.PasswordPolicy;
import com.leyon.lumin.identity.repository.PasswordCredentialMapper;
import com.leyon.lumin.identity.repository.PasswordCredentialRow;
import com.leyon.lumin.identity.repository.PasswordResetTicketMapper;
import com.leyon.lumin.identity.repository.PasswordResetTicketRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 口令找回 / 重置（V24；2026-11-20 安全审查 04 项收尾），双通道：
 *
 * <ul>
 *   <li><b>EMAIL</b>：请求即生成一次性重置码（30 分钟、单次、只存 SHA-256），
 *       经 {@link PasswordResetMailGateway} 投递；**不把码回给请求者**（否则等于无凭据改密）；</li>
 *   <li><b>CUSTOMER_SERVICE</b>：请求只建工单（{@code AWAITING_OPERATOR}），
 *       OPERATOR/ADMIN 核验身份后批准，重置码只在批准回执里返回一次（客服口述给用户）。</li>
 * </ul>
 *
 * <p>安全口径：
 * <ol>
 *   <li>请求接口对"登录名不存在 / 已存在"返回同一 200，不可枚举账号；</li>
 *   <li>同一登录名同一时间只有一张未结束工单——重复请求不再发信（防邮件轰炸）；</li>
 *   <li>重置成功后撤销该主体**全部会话**（旧令牌立即失效）；</li>
 *   <li>重置**不动** MFA 状态：第二因子仍拦得住（重置请求本身可能是攻击者发起的）；</li>
 *   <li>口令仍走 {@link PasswordPolicy} 与 BCrypt（复用 {@link PasswordCredentialService} 的强度）。</li>
 * </ol>
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /** 重置码有效期。 */
    private static final Duration CODE_TTL = Duration.ofMinutes(30);
    /** 重置码字符集（去掉易混淆的 0/O/1/I，与恢复码同一口径）。 */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** 重置码长度（明文）。 */
    private static final int CODE_LENGTH = 10;
    /** 可用通道。 */
    private static final Set<String> CHANNELS = Set.of("EMAIL", "CUSTOMER_SERVICE");
    /** 可裁决角色。 */
    private static final Set<String> REVIEWER_ROLES = Set.of("OPERATOR", "ADMIN");
    /** 客服工单列表上限。 */
    private static final int LIST_LIMIT = 100;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordCredentialMapper credentialMapper;
    private final PasswordResetTicketMapper ticketMapper;
    private final PasswordResetMailGateway mailGateway;
    private final AuthSessionService sessionService;

    public PasswordResetService(PasswordCredentialMapper credentialMapper,
                                PasswordResetTicketMapper ticketMapper,
                                PasswordResetMailGateway mailGateway,
                                AuthSessionService sessionService) {
        this.credentialMapper = credentialMapper;
        this.ticketMapper = ticketMapper;
        this.mailGateway = mailGateway;
        this.sessionService = sessionService;
    }

    /** 请求回执（对存在/不存在的登录名完全一致）。 */
    public record RequestResult(boolean accepted, String channel) {
    }

    /** 确认回执（不发会话令牌：重置后仍需正常登录，从而不会绕过 MFA）。 */
    public record ResetResult(String loginId, String subject) {
    }

    /** 客服工单视图（不含码摘要/明文）。 */
    public record TicketView(String id, String loginId, String channel, String state,
                             String createdAt, String expiresAt) {
    }

    /** 客服批准回执：重置码只在此出现一次（由客服口述，不再落库）。 */
    public record IssuedCode(String id, String resetCode, String expiresAt) {
    }

    /**
     * 请求重置。无论账号是否存在都返回同一结果（防枚举）。
     */
    @Transactional
    public RequestResult request(String rawLoginId, String rawChannel, LocalDateTime now) {
        String channel = rawChannel == null ? "" : rawChannel.trim().toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(channel)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String loginId = PasswordCredentialService.normalizeLoginId(rawLoginId);
        PasswordCredentialRow credential = loginId == null ? null
                : credentialMapper.findByLoginId(loginId);
        if (credential == null || !"ACTIVE".equals(credential.state())) {
            // 不建工单、不投递，但响应与成功路径一致
            log.info("口令重置请求 event=password.reset result=accepted reason=no_account "
                    + "channel={}", channel);
            return new RequestResult(true, channel);
        }
        if (ticketMapper.findOpenByLogin(loginId) != null) {
            // 已有未结束工单：不再发第二封（防邮件轰炸 / 码歧义）
            log.info("口令重置请求 event=password.reset result=accepted reason=ticket_open "
                    + "loginId={} channel={}", loginId, channel);
            return new RequestResult(true, channel);
        }
        if ("CUSTOMER_SERVICE".equals(channel)) {
            insertTicket(loginId, credential.subject(), channel, "AWAITING_OPERATOR",
                    null, null, now);
            log.info("口令重置工单 event=password.reset result=awaiting_operator loginId={}",
                    loginId);
            return new RequestResult(true, channel);
        }
        String email = credentialMapper.findEmailByLoginId(loginId);
        if (email == null) {
            // 账号没有邮箱：落一张 DELIVERY_FAILED 工单，运营可见并引导走客服通道
            insertTicket(loginId, credential.subject(), channel, "DELIVERY_FAILED",
                    null, null, now);
            log.warn("口令重置邮件不可投递 event=password.reset result=delivery_failed "
                    + "reason=no_email loginId={}", loginId);
            return new RequestResult(true, channel);
        }
        String code = generateCode();
        LocalDateTime expiresAt = now.plus(CODE_TTL);
        String ticketId = insertTicket(loginId, credential.subject(), channel, "PENDING",
                sha256(normalizeCode(code)), expiresAt, now);
        try {
            mailGateway.send(email, code, expiresAt);
            log.info("口令重置邮件已投递 event=password.reset result=mail_sent loginId={} "
                    + "expiresAt={}", loginId, expiresAt);
        } catch (RuntimeException e) {
            // 通道不可用：工单转 DELIVERY_FAILED 交人工，不假装成功
            ticketMapper.markState(ticketId, "PENDING", "DELIVERY_FAILED", "system", now);
            log.error("口令重置邮件投递失败 event=password.reset result=delivery_failed "
                    + "reason={} loginId={}", e.getClass().getSimpleName(), loginId);
        }
        return new RequestResult(true, channel);
    }

    /**
     * 用重置码改密：码必须命中未过期工单；成功后撤销全部会话（不签发新会话）。
     */
    @Transactional(noRollbackFor = LuminBusinessException.class)
    public ResetResult confirm(String rawLoginId, String rawCode, String newPassword,
                               LocalDateTime now) {
        String loginId = PasswordCredentialService.normalizeLoginId(rawLoginId);
        String code = normalizeCode(rawCode);
        if (loginId == null || code.isEmpty() || newPassword == null || newPassword.isEmpty()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        PasswordResetTicketRow ticket = ticketMapper.findOpenByLogin(loginId);
        if (ticket == null || !"PENDING".equals(ticket.state()) || ticket.codeHash() == null) {
            log.warn("口令重置确认被拒 event=password.reset result=rejected reason=no_ticket "
                    + "loginId={}", loginId);
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        if (ticket.expiresAt() != null && now.isAfter(ticket.expiresAt())) {
            ticketMapper.markState(ticket.id(), "PENDING", "EXPIRED", "system", now);
            log.warn("口令重置确认被拒 event=password.reset result=rejected reason=expired "
                    + "loginId={}", loginId);
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        if (!MessageDigest.isEqual(ticket.codeHash().getBytes(StandardCharsets.US_ASCII),
                sha256(code).getBytes(StandardCharsets.US_ASCII))) {
            log.warn("口令重置确认被拒 event=password.reset result=rejected reason=bad_code "
                    + "loginId={}", loginId);
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        PasswordPolicy.Violation violation = PasswordPolicy.check(newPassword, loginId);
        if (violation != null) {
            log.warn("口令重置确认被拒 event=password.reset result=rejected reason={} "
                    + "loginId={}", violation, loginId);
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        PasswordCredentialRow credential = credentialMapper.findByLoginId(loginId);
        if (credential == null || !"ACTIVE".equals(credential.state())) {
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        if (ticketMapper.markConsumed(ticket.id(), "system", now, ticket.lockVersion()) == 0) {
            // 并发消费：视为无效码
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        credentialMapper.updatePassword(credential.id(),
                PasswordCredentialService.hashPassword(newPassword), now);
        int revoked = sessionService.revokeAllFor(credential.subject());
        log.info("口令重置成功 event=password.reset result=ok subject={} loginId={} "
                + "revokedSessions={}", credential.subject(), loginId, revoked);
        return new ResetResult(loginId, credential.subject());
    }

    /** 客服待处理工单（OPERATOR/ADMIN）。 */
    public List<TicketView> listAwaiting(String role) {
        requireReviewer(role);
        return ticketMapper.listAwaiting(LIST_LIMIT).stream()
                .map(row -> new TicketView(row.id(), row.loginId(), row.channel(), row.state(),
                        row.createdAt().toInstant(ZoneOffset.UTC).toString(),
                        row.expiresAt() == null ? null
                                : row.expiresAt().toInstant(ZoneOffset.UTC).toString()))
                .toList();
    }

    /** 客服裁决：批准 → 生成重置码并回给操作员一次；驳回 → REJECTED。 */
    @Transactional
    public IssuedCode decide(String operator, String role, String ticketId, boolean approve,
                             LocalDateTime now) {
        requireReviewer(role);
        PasswordResetTicketRow ticket = ticketId == null ? null : ticketMapper.findById(ticketId);
        if (ticket == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (!"AWAITING_OPERATOR".equals(ticket.state())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        if (!approve) {
            if (ticketMapper.markState(ticket.id(), "AWAITING_OPERATOR", "REJECTED", operator,
                    now) == 0) {
                throw new LuminBusinessException(ErrorCode.B2001);
            }
            log.info("口令重置工单被驳回 event=password.reset result=rejected ticketId={}",
                    ticket.id());
            return new IssuedCode(ticket.id(), null, null);
        }
        String code = generateCode();
        LocalDateTime expiresAt = now.plus(CODE_TTL);
        if (ticketMapper.markPendingWithCode(ticket.id(), sha256(normalizeCode(code)), expiresAt,
                now, operator) == 0) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        log.warn("客服已签发口令重置码 event=password.reset result=code_issued ticketId={} "
                + "loginId={} expiresAt={}", ticket.id(), ticket.loginId(), expiresAt);
        return new IssuedCode(ticket.id(), code, expiresAt.toInstant(ZoneOffset.UTC).toString());
    }

    private String insertTicket(String loginId, String subject, String channel, String state,
                                String codeHash, LocalDateTime expiresAt, LocalDateTime now) {
        String id = UUID.randomUUID().toString();
        ticketMapper.insert(new PasswordResetTicketRow(id, loginId, subject, channel, state,
                codeHash, expiresAt, null, now, now, subject, subject, 1L));
        return id;
    }

    private static void requireReviewer(String role) {
        if (role == null || !REVIEWER_ROLES.contains(role)) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
    }

    /** 10 位随机码（展示为 5-5 便于口述，入库前归一化去分隔符）。 */
    private static String generateCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH + 1);
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (i == 5) {
                builder.append('-');
            }
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    private static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(Character.toUpperCase(c));
            }
        }
        return builder.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }
}
