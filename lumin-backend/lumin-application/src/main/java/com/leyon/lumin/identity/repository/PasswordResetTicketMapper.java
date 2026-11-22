package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** password_reset_ticket 表数据访问（V24；双通道口令重置工单）。 */
@Mapper
public interface PasswordResetTicketMapper {

    @Insert("""
            INSERT INTO password_reset_ticket (id, login_id, subject, channel, state, code_hash,
                expires_at, consumed_at, created_at, updated_at, created_by, updated_by,
                lock_version)
            VALUES (#{row.id}, #{row.loginId}, #{row.subject}, #{row.channel}, #{row.state},
                #{row.codeHash}, #{row.expiresAt}, #{row.consumedAt}, #{row.createdAt},
                #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") PasswordResetTicketRow row);

    @Select("SELECT * FROM password_reset_ticket WHERE id = #{id}")
    PasswordResetTicketRow findById(@Param("id") String id);

    /** 该登录名当前"未结束"的工单（PENDING/AWAITING_OPERATOR；用于抑制重复发信）。 */
    @Select("""
            SELECT * FROM password_reset_ticket
            WHERE login_id = #{loginId} AND state IN ('PENDING', 'AWAITING_OPERATOR')
            ORDER BY created_at DESC, id LIMIT 1
            """)
    PasswordResetTicketRow findOpenByLogin(@Param("loginId") String loginId);

    /** 待人工核验工单（客服通道，时间正序）。 */
    @Select("""
            SELECT * FROM password_reset_ticket WHERE state = 'AWAITING_OPERATOR'
            ORDER BY created_at, id LIMIT #{limit}
            """)
    List<PasswordResetTicketRow> listAwaiting(@Param("limit") int limit);

    /** 客服批准后落码（AWAITING_OPERATOR 守卫；返回 0 表示已被他人处理）。 */
    @Update("""
            UPDATE password_reset_ticket
            SET state = 'PENDING', code_hash = #{codeHash}, expires_at = #{expiresAt},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND state = 'AWAITING_OPERATOR'
            """)
    int markPendingWithCode(@Param("id") String id, @Param("codeHash") String codeHash,
                            @Param("expiresAt") LocalDateTime expiresAt,
                            @Param("now") LocalDateTime now,
                            @Param("operator") String operator);

    /** 消费（PENDING 守卫 + 乐观锁；返回 0 表示并发已改态）。 */
    @Update("""
            UPDATE password_reset_ticket
            SET state = 'CONSUMED', consumed_at = #{now}, updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND state = 'PENDING' AND lock_version = #{expectedLockVersion}
            """)
    int markConsumed(@Param("id") String id, @Param("operator") String operator,
                     @Param("now") LocalDateTime now,
                     @Param("expectedLockVersion") long expectedLockVersion);

    /** 旁支状态迁移（DELIVERY_FAILED / EXPIRED / REJECTED；按当前状态守卫）。 */
    @Update("""
            UPDATE password_reset_ticket
            SET state = #{target}, updated_at = #{now}, updated_by = #{operator},
                lock_version = lock_version + 1
            WHERE id = #{id} AND state = #{expectedState}
            """)
    int markState(@Param("id") String id, @Param("expectedState") String expectedState,
                  @Param("target") String target, @Param("operator") String operator,
                  @Param("now") LocalDateTime now);
}
