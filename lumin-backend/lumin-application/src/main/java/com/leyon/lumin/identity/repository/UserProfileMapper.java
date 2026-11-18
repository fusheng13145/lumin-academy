package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** user_profile 表数据访问（04 §3；角色/年龄从权威记录读取，INV-07）。 */
@Mapper
public interface UserProfileMapper {

    @Select("SELECT * FROM user_profile WHERE id = #{id} AND deleted_at IS NULL")
    UserProfileRow findById(@Param("id") String id);

    /** 注销立即公开匿名：昵称匿名 + CLOSED + closed_at（乐观锁兜底并发）。 */
    @Update("""
            UPDATE user_profile SET nickname = #{nickname}, account_status = 'CLOSED',
                closed_at = #{now}, updated_at = #{now}, updated_by = #{operator},
                lock_version = lock_version + 1
            WHERE id = #{id} AND account_status <> 'CLOSED'
            """)
    int applyClosure(@Param("id") String id, @Param("nickname") String nickname,
                     @Param("now") java.time.LocalDateTime now,
                     @Param("operator") String operator);

    /** 内部关联匿名化（注销满 6 个月；审计事实不删，OIDC 关联断开不可逆）。 */
    @Update("""
            UPDATE user_profile SET oidc_issuer = #{issuer}, oidc_subject = #{subject},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id}
            """)
    int anonymizeInternal(@Param("id") String id, @Param("issuer") String issuer,
                          @Param("subject") String subject,
                          @Param("operator") String operator,
                          @Param("now") java.time.LocalDateTime now);
}
