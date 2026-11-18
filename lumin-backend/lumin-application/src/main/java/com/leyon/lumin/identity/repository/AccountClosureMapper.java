package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** account_closure 表数据访问（03 §8 注销推进；审计事实不删）。 */
@Mapper
public interface AccountClosureMapper {

    @Insert("""
            INSERT INTO account_closure (id, user_id, state, requested_at, anonymize_due_at,
                hold_id, created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.userId}, #{row.state}, #{row.requestedAt},
                #{row.anonymizeDueAt}, #{row.holdId}, #{row.createdAt}, #{row.updatedAt},
                #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") AccountClosureRow row);

    @Select("SELECT * FROM account_closure WHERE user_id = #{userId}")
    AccountClosureRow findByUser(@Param("userId") String userId);

    /** 到期待匿名化推进（PENDING/HELD；无 ACTIVE 保全者由服务层过滤）。 */
    @Select("""
            SELECT * FROM account_closure
            WHERE state IN ('PENDING','HELD') AND anonymize_due_at <= #{now}
            """)
    List<AccountClosureRow> listDue(@Param("now") LocalDateTime now);

    /** 状态推进（乐观锁；HELD→ANONYMIZED 仅在保全解除后）。 */
    @Update("""
            UPDATE account_closure SET state = #{state}, hold_id = #{holdId},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND state = #{expectedState} AND lock_version = #{expectedLockVersion}
            """)
    int transition(@Param("id") String id, @Param("state") String state,
                   @Param("holdId") String holdId, @Param("expectedState") String expectedState,
                   @Param("expectedLockVersion") long expectedLockVersion,
                   @Param("operator") String operator, @Param("now") LocalDateTime now);
}
