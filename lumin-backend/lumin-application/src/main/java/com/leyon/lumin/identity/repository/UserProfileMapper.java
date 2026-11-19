package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** user_profile 表数据访问（04 §3；角色/年龄从权威记录读取，INV-07）。 */
@Mapper
public interface UserProfileMapper {

    @Select("SELECT * FROM user_profile WHERE id = #{id} AND deleted_at IS NULL")
    UserProfileRow findById(@Param("id") String id);

    /** 首次建档（05 §3.1 createProfile）：未核验有效层由服务层从严写 C4。 */
    @Insert("""
            INSERT INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
                birth_date_cipher, declared_age_band, verified_age_band,
                effective_age_band, age_verified_at, account_status, policy_revision,
                created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.oidcIssuer}, #{row.oidcSubject}, #{row.nickname},
                #{row.birthDateCipher}, #{row.declaredAgeBand}, #{row.verifiedAgeBand},
                #{row.effectiveAgeBand}, #{row.ageVerifiedAt}, #{row.accountStatus},
                #{row.policyRevision}, #{now}, #{now}, #{row.createdBy}, #{row.updatedBy}, 1)
            """)
    int insertProfile(@Param("row") UserProfileRow row,
                      @Param("now") java.time.LocalDateTime now);

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

    /** 数据主体更正权（UGC-006 CORRECT：公开昵称为唯一可更正公开字段）。 */
    @Update("""
            UPDATE user_profile SET nickname = #{nickname}, updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id}
            """)
    int applyCorrection(@Param("id") String id, @Param("nickname") String nickname,
                        @Param("operator") String operator,
                        @Param("now") java.time.LocalDateTime now);
}
