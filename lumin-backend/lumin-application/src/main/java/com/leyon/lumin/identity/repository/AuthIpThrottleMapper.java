package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/** auth_ip_throttle 表数据访问（V22；按 IP 的固定窗口计数）。 */
@Mapper
public interface AuthIpThrottleMapper {

    /** 计数 +1（同 (ip, 窗口) 原子累加，多实例并发安全）。 */
    @Insert("""
            INSERT INTO auth_ip_throttle (ip_hash, window_start, attempts, created_at, updated_at)
            VALUES (#{ipHash}, #{windowStart}, 1, #{now}, #{now})
            ON DUPLICATE KEY UPDATE attempts = attempts + 1, updated_at = #{now}
            """)
    int increment(@Param("ipHash") String ipHash,
                  @Param("windowStart") LocalDateTime windowStart,
                  @Param("now") LocalDateTime now);

    /** 当前窗口已计数的尝试次数（无行返回 null）。 */
    @Select("""
            SELECT attempts FROM auth_ip_throttle
            WHERE ip_hash = #{ipHash} AND window_start = #{windowStart}
            """)
    Integer attempts(@Param("ipHash") String ipHash,
                     @Param("windowStart") LocalDateTime windowStart);

    /** 清理陈旧窗口（只在新窗口首次计数时调用，避免每请求一次写放大）。 */
    @Delete("DELETE FROM auth_ip_throttle WHERE window_start < #{cutoff}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);
}
