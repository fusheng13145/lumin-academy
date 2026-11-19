package com.leyon.lumin.engagement.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * subscription 表数据访问（docs/04 §6.1；V17；docs/13 UGC-036）。
 * U(user_id,work_id)；跨账号隔离按主体查询；digest 去重表由 UGC-037 写入。
 */
@Mapper
public interface SubscriptionMapper {

    /**
     * 订阅（重复订阅仅重开 enabled=1 并刷新审计列，保留既有免打扰/推送偏好）。
     */
    @Insert("""
            INSERT INTO subscription (id, user_id, work_id, enabled, quiet_start, quiet_end,
                push_enabled, timezone_id, created_at, created_by, updated_at, updated_by,
                lock_version)
            VALUES (#{row.id}, #{row.userId}, #{row.workId}, #{row.enabled}, #{row.quietStart},
                #{row.quietEnd}, #{row.pushEnabled}, #{row.timezoneId}, #{row.createdAt},
                #{row.createdBy}, #{row.updatedAt}, #{row.updatedBy}, #{row.lockVersion})
            ON DUPLICATE KEY UPDATE enabled = 1, updated_at = VALUES(updated_at),
                updated_by = VALUES(updated_by)
            """)
    int upsert(@Param("row") SubscriptionRow row);

    @Select("""
            SELECT COUNT(*) FROM subscription
            WHERE user_id = #{userId} AND work_id = #{workId} AND enabled = 1
            """)
    int existsEnabled(@Param("userId") String userId, @Param("workId") String workId);

    /** 本人订阅行（含已退订删除前的偏好读取；无行返回 null）。 */
    @Select("SELECT * FROM subscription WHERE user_id = #{userId} AND work_id = #{workId}")
    SubscriptionRow findByUserAndWork(@Param("userId") String userId,
                                      @Param("workId") String workId);

    /** 偏好更新（仅 enabled 行；lock_version 自增）。 */
    @Update("""
            UPDATE subscription SET quiet_start = #{row.quietStart},
                quiet_end = #{row.quietEnd}, push_enabled = #{row.pushEnabled},
                timezone_id = #{row.timezoneId}, updated_at = #{row.updatedAt},
                updated_by = #{row.updatedBy}, lock_version = lock_version + 1
            WHERE user_id = #{row.userId} AND work_id = #{row.workId} AND enabled = 1
            """)
    int updatePreferences(@Param("row") SubscriptionRow row);

    /** 订阅列表（订阅时间倒序 + 作品元数据；含业务态供前端灰显下架作品）。 */
    @Select("""
            SELECT s.id AS subscriptionId, s.work_id AS workId,
                   s.created_at AS subscribedAt, s.enabled, s.quiet_start AS quietStart,
                   s.quiet_end AS quietEnd, s.push_enabled AS pushEnabled,
                   s.timezone_id AS timezoneId, w.business_state AS workState,
                   cv.title, COALESCE(u.nickname, '') AS author
            FROM subscription s
            JOIN work w ON w.id = s.work_id AND w.deleted_at IS NULL
            LEFT JOIN publication_pointer p ON p.target_type = 'WORK' AND p.target_id = s.work_id
            LEFT JOIN content_version cv ON cv.id = p.version_id AND cv.state = 'APPROVED'
            LEFT JOIN user_profile u ON u.id = w.author_id AND u.deleted_at IS NULL
            WHERE s.user_id = #{userId} AND s.enabled = 1
            ORDER BY s.created_at DESC, s.id
            LIMIT #{limit}
            """)
    List<SubscriptionListItem> listByUser(@Param("userId") String userId,
                                          @Param("limit") int limit);

    /** 退订（按主体+作品删除；跨账号隔离；幂等）。 */
    @Delete("DELETE FROM subscription WHERE user_id = #{userId} AND work_id = #{workId}")
    int deleteByUserAndWork(@Param("userId") String userId, @Param("workId") String workId);
}
