package com.leyon.lumin.startup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 安全配置启动门禁（2026-11-20 安全与质量审查修复）。
 *
 * <p>把两类"部署错误"从运行期失败提前到**启动失败**，避免静默降级：
 * <ol>
 *   <li>游标签名密钥缺失：{@code lumin.cursor.key} 为空时游标签名不可信（原实现
 *       在 {@code application.yaml} 内置 {@code lumin-cursor-dev-key} 默认值，
 *       导致 {@code CursorCodec} 的 fail-fast 永不触发）；</li>
 *   <li>非开发环境仍在使用 demo 身份头：{@code lumin.auth.mode=demo} 下任何请求
 *       只要带 {@code X-Lumin-Demo-Subject: ADMIN} 即获得管理员主体，生产环境
 *       必须为 {@code session}（{@code LUMIN_AUTH_MODE}）。</li>
 * </ol>
 * 本类只做配置校验，不承载任何认证逻辑（认证仍在 {@code DemoAuthenticationFilter}
 * 与 {@code AuthSessionService}）。字段加密密钥的空值校验由 {@code FieldCipher}
 * 自身 fail-fast 承担，此处不重复。
 */
@Component
public class SecurityStartupValidator {

    /** 视为开发/测试的环境标识（其余一律按生产口径收紧）。 */
    private static final java.util.Set<String> DEV_ENVIRONMENTS =
            java.util.Set.of("dev", "local", "test", "ci");

    public SecurityStartupValidator(
            @Value("${lumin.cursor.key:}") String cursorKey,
            @Value("${lumin.auth.mode:demo}") String authMode,
            @Value("${lumin.deployment.environment:dev}") String environment,
            @Value("${lumin.auth.reset.mail-mode:unconfigured}") String resetMailMode,
            com.leyon.lumin.identity.service.PasswordResetMailGateway resetMailGateway) {
        if (cursorKey == null || cursorKey.isBlank()) {
            throw new IllegalStateException(
                    "游标签名密钥缺失：请通过环境变量 LUMIN_CURSOR_KEY 注入"
                            + "（禁止以空密钥/开发默认值启动，否则游标可被伪造）");
        }
        String env = environment == null ? "dev" : environment.trim().toLowerCase();
        if (!DEV_ENVIRONMENTS.contains(env) && !"session".equalsIgnoreCase(authMode)) {
            throw new IllegalStateException(
                    "非开发环境禁止 demo 认证模式：environment=" + env
                            + ", lumin.auth.mode=" + authMode
                            + "；请设置 LUMIN_AUTH_MODE=session 后再启动");
        }
        // 口令找回邮件通道（V24）：模式必须受支持；配了具体模式却没提供实现 → 启动失败，
        // 而不是运行期悄悄不投递
        String mailMode = resetMailMode == null ? "unconfigured"
                : resetMailMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"unconfigured".equals(mailMode) && !"smtp".equals(mailMode)) {
            throw new IllegalStateException(
                    "lumin.auth.reset.mail-mode=" + resetMailMode + " 不受支持："
                            + "只允许 unconfigured / smtp（拼错会静默降级成不投递）");
        }
        if (!"unconfigured".equals(mailMode)
                && resetMailGateway instanceof
                com.leyon.lumin.identity.service.UnconfiguredPasswordResetMailGateway) {
            throw new IllegalStateException(
                    "lumin.auth.reset.mail-mode=" + mailMode
                            + " 但没有提供 PasswordResetMailGateway 实现（仍为 unconfigured 兜底）："
                            + "请由部署侧注册真实邮件网关，或改回 unconfigured 并走客服通道");
        }
    }
}
