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

    /** 队列页作者年龄带批量读取，避免逐作者 N+1（增量五十）。 */
    @Select("""
            <script>
            SELECT * FROM user_profile WHERE deleted_at IS NULL AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    java.util.List<UserProfileRow> findByIds(@Param("ids") java.util.List<String> ids);

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

    /** 年龄核验成功：写已核验/有效层与核验时间，策略版本前进（INV-07 只认权威记录）。 */
    @Update("""
            UPDATE user_profile SET verified_age_band = #{verifiedBand},
                effective_age_band = #{effectiveBand}, age_verified_at = #{verifiedAt},
                policy_revision = policy_revision + 1, updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int applyAgeVerification(@Param("id") String id,
                             @Param("verifiedBand") String verifiedBand,
                             @Param("effectiveBand") String effectiveBand,
                             @Param("verifiedAt") java.time.LocalDateTime verifiedAt,
                             @Param("now") java.time.LocalDateTime now,
                             @Param("operator") String operator);

    /** 监护撤销立即降级：仅未成年（C0/C1/C2）落 C4，成年已核验不受影响。 */
    @Update("""
            UPDATE user_profile SET effective_age_band = 'C4', updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND deleted_at IS NULL
                AND verified_age_band IN ('C0','C1','C2')
            """)
    int downgradeAfterConsentRevocation(@Param("id") String id,
                                        @Param("now") java.time.LocalDateTime now,
                                        @Param("operator") String operator);

    /** 有效监护同意恢复未成年有效层：仅回填已核验层，未核验仍维持 C4。 */
    @Update("""
            UPDATE user_profile SET effective_age_band = verified_age_band,
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND deleted_at IS NULL
                AND verified_age_band IN ('C0','C1','C2')
            """)
    int restoreAfterConsent(@Param("id") String id,
                            @Param("now") java.time.LocalDateTime now,
                            @Param("operator") String operator);

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
