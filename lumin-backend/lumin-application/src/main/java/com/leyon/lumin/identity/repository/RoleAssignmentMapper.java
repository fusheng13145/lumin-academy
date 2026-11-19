package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** role_assignment 表数据访问（docs/04 §3；角色绑定权威来源，grant_id 外键 V8 补建）。 */
@Mapper
public interface RoleAssignmentMapper {

    /** 绑定（授予链 APPROVED 后调用；grant_id 外键；U(user,role,scope,active_slot) 兜底并发）。 */
    @Insert("""
            INSERT INTO role_assignment (id, user_id, role, scope_type, scope_id,
                expires_at, grant_id, created_at, updated_at, created_by, updated_by,
                lock_version)
            VALUES (#{id}, #{userId}, #{role}, #{scopeType}, #{scopeId}, #{expiresAt},
                #{grantId}, #{now}, #{now}, #{operator}, #{operator}, 1)
            """)
    int insertActive(@Param("id") String id, @Param("userId") String userId,
                     @Param("role") String role, @Param("scopeType") String scopeType,
                     @Param("scopeId") String scopeId,
                     @Param("expiresAt") LocalDateTime expiresAt,
                     @Param("grantId") String grantId,
                     @Param("now") LocalDateTime now, @Param("operator") String operator);

    /** 有效绑定（未软删且未过期）。 */
    @Select("""
            SELECT * FROM role_assignment WHERE user_id = #{userId}
                AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at > #{now})
            ORDER BY created_at DESC, id
            """)
    List<RoleGrantRow> findActiveBindings(@Param("userId") String userId,
                                          @Param("now") LocalDateTime now);

    /** 有效角色码（/me 的 roles 权威来源；去重、稳定排序）。 */
    @Select("""
            SELECT DISTINCT role FROM role_assignment WHERE user_id = #{userId}
                AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at > #{now})
            ORDER BY role
            """)
    List<String> findActiveRoleCodes(@Param("userId") String userId,
                                     @Param("now") LocalDateTime now);
}
