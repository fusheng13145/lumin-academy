package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** role_grant 表数据访问（docs/04 §5.2 identity；UGC-004 授予链）。 */
@Mapper
public interface RoleGrantMapper {

    @Insert("""
            INSERT INTO role_grant (id, target_user_id, role, scope_type, scope_id,
                expires_at, reason, state, requested_by, reviewed_by, reviewed_at,
                created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.targetUserId}, #{row.role}, #{row.scopeType},
                #{row.scopeId}, #{row.expiresAt}, #{row.reason}, #{row.state},
                #{row.requestedBy}, #{row.reviewedBy}, #{row.reviewedAt},
                #{row.createdAt}, #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy},
                #{row.lockVersion})
            """)
    int insert(@Param("row") RoleGrantRow row);

    @Select("SELECT * FROM role_grant WHERE id = #{id} FOR UPDATE")
    RoleGrantRow findByIdForUpdate(@Param("id") String id);

    /** 详情读取（非锁定；授权过滤由服务层再做一次，避免 TOCTOU 读取他人记录）。 */
    @Select("SELECT * FROM role_grant WHERE id = #{id}")
    RoleGrantRow findById(@Param("id") String id);

    /**
     * 可见授予列表（05 §3.2 listRoleGrants）：ADMIN 可全量；其他主体仅本人提案
     * 或本人被授予。先授权过滤再分页；游标按 created_at DESC, id DESC。
     */
    @Select("""
            SELECT * FROM role_grant
            WHERE (#{includeAll} = TRUE
                OR requested_by = #{requester} OR target_user_id = #{requester})
              AND (#{cursorAt,jdbcType=TIMESTAMP} IS NULL
                OR created_at < #{cursorAt,jdbcType=TIMESTAMP}
                OR (created_at = #{cursorAt,jdbcType=TIMESTAMP}
                    AND id < #{cursorId,jdbcType=VARCHAR}))
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<RoleGrantRow> listVisible(@Param("requester") String requester,
                                   @Param("includeAll") boolean includeAll,
                                   @Param("cursorAt") LocalDateTime cursorAt,
                                   @Param("cursorId") String cursorId,
                                   @Param("limit") int limit);

    /** 审批（乐观锁 + PENDING 守卫；REJECTED 亦走本路径留痕）。 */
    @Update("""
            UPDATE role_grant SET state = #{state}, reviewed_by = #{reviewedBy},
                reviewed_at = #{reviewedAt}, updated_at = #{now}, updated_by = #{operator},
                lock_version = lock_version + 1
            WHERE id = #{id} AND state = 'PENDING' AND lock_version = #{expectedLockVersion}
            """)
    int review(@Param("id") String id, @Param("state") String state,
               @Param("reviewedBy") String reviewedBy,
               @Param("reviewedAt") LocalDateTime reviewedAt,
               @Param("operator") String operator, @Param("now") LocalDateTime now,
               @Param("expectedLockVersion") long expectedLockVersion);

    /** 有效授予（APPROVED 且未过期；时间倒序取最新）。 */
    @Select("""
            SELECT * FROM role_grant WHERE target_user_id = #{userId}
                AND state = 'APPROVED'
                AND (expires_at IS NULL OR expires_at > #{now})
            ORDER BY created_at DESC, id
            """)
    List<RoleGrantRow> findActiveBySubject(@Param("userId") String userId,
                                           @Param("now") LocalDateTime now);
}
