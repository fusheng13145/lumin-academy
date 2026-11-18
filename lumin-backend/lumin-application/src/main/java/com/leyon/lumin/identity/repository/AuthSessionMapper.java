package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** auth_session 表数据访问（UGC-002；服务端权威会话，不存原始令牌）。 */
@Mapper
public interface AuthSessionMapper {

    @Insert("""
            INSERT INTO auth_session (id, token_hash, subject, stub_name, role_context,
                issued_at, expires_at, revoked_at, created_at, updated_at,
                created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.tokenHash}, #{row.subject}, #{row.stubName},
                #{row.roleContext}, #{row.issuedAt}, #{row.expiresAt}, #{row.revokedAt},
                #{row.createdAt}, #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy},
                #{row.lockVersion})
            """)
    int insert(@Param("row") AuthSessionRow row);

    @Select("SELECT * FROM auth_session WHERE token_hash = #{tokenHash}")
    AuthSessionRow findByTokenHash(@Param("tokenHash") String tokenHash);

    /** 撤销单会话（幂等：revoked_at IS NULL 守卫，重复撤销 0 行）。 */
    @Update("""
            UPDATE auth_session SET revoked_at = #{now}, updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE token_hash = #{tokenHash} AND revoked_at IS NULL
            """)
    int revoke(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now,
               @Param("operator") String operator);

    /** 注销联动：撤销该主体全部有效会话。 */
    @Update("""
            UPDATE auth_session SET revoked_at = #{now}, updated_at = #{now},
                updated_by = 'system', lock_version = lock_version + 1
            WHERE subject = #{subject} AND revoked_at IS NULL
            """)
    int revokeAllBySubject(@Param("subject") String subject,
                           @Param("now") LocalDateTime now);
}
