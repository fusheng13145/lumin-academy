-- V22：按客户端 IP 的认证尝试配额（2026-11-20 安全审查 19 项"按 IP 登录限流"）
--
-- 为什么落库而不是内存/Redis：账号级失败锁定已经落库（V20），
-- 按 IP 的配额同样需要"多实例一致 + 重启不丢"，否则副本越多配额越松。
-- 单实例内存窗口是 P0 的已知降级（RateLimitFilter 已如实登记），
-- 这里不再重复那种口径。
--
-- 隐私：只存 **加盐 SHA-256**（salt = lumin.crypto.key，与 FieldCipher 同源），
-- 不存原始地址；salt 轮换只会重置窗口计数，无功能影响。
-- 行数：每个活跃 (ip, 分钟) 一行，服务层在新窗口首行写入时顺手清理 2 小时前的行。
CREATE TABLE auth_ip_throttle (
    ip_hash      CHAR(64)    NOT NULL,
    window_start DATETIME(6) NOT NULL,
    attempts     INT         NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    CONSTRAINT pk_auth_ip_throttle PRIMARY KEY (ip_hash, window_start),
    CONSTRAINT ck_auth_ip_throttle_attempts CHECK (attempts > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_auth_ip_throttle_window ON auth_ip_throttle (window_start);
