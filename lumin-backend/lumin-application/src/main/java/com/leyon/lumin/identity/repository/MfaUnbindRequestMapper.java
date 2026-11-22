package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** mfa_unbind_request 表数据访问（V23；解绑工单录入、查询与裁决）。 */
@Mapper
public interface MfaUnbindRequestMapper {

    @Insert("""
            INSERT INTO mfa_unbind_request (id, subject, reason, state, requested_at,
                reviewed_by, reviewed_at, created_at, updated_at, created_by, updated_by,
                lock_version)
            VALUES (#{row.id}, #{row.subject}, #{row.reason}, #{row.state}, #{row.requestedAt},
                #{row.reviewedBy}, #{row.reviewedAt}, #{row.createdAt}, #{row.updatedAt},
                #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") MfaUnbindRequestRow row);

    @Select("SELECT * FROM mfa_unbind_request WHERE id = #{id}")
    MfaUnbindRequestRow findById(@Param("id") String id);

    /** 主体当前待处理工单（null 表示没有）。 */
    @Select("SELECT * FROM mfa_unbind_request WHERE subject = #{subject} AND state = 'PENDING'")
    MfaUnbindRequestRow findPendingBySubject(@Param("subject") String subject);

    /** 待处理工单列表（运营/管理员视角，时间正序——先来的先处理）。 */
    @Select("""
            SELECT * FROM mfa_unbind_request WHERE state = 'PENDING'
            ORDER BY requested_at, id LIMIT #{limit}
            """)
    List<MfaUnbindRequestRow> listPending(@Param("limit") int limit);

    /** 裁决（PENDING 守卫 + 乐观锁；返回 0 表示已被他人处理）。 */
    @Update("""
            UPDATE mfa_unbind_request
            SET state = #{state}, reviewed_by = #{reviewer}, reviewed_at = #{now},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND state = 'PENDING' AND lock_version = #{expectedLockVersion}
            """)
    int decide(@Param("id") String id, @Param("state") String state,
               @Param("reviewer") String reviewer, @Param("now") LocalDateTime now,
               @Param("operator") String operator, @Param("expectedLockVersion") long expectedLockVersion);
}
