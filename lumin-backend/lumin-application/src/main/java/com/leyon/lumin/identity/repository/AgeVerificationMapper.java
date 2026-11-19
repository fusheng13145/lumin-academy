package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** age_verification 表数据访问（docs/04 §3；不得以自述作为 VERIFIED）。 */
@Mapper
public interface AgeVerificationMapper {

    @Insert("""
            INSERT INTO age_verification (id, user_id, provider, proof_ref_cipher,
                proof_digest, method, result, age_band, verified_at, expires_at,
                created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.userId}, #{row.provider}, #{row.proofRefCipher},
                #{row.proofDigest}, #{row.method}, #{row.result}, #{row.ageBand},
                #{row.verifiedAt}, #{row.expiresAt}, #{row.createdAt}, #{row.updatedAt},
                #{row.createdBy}, #{row.updatedBy}, 1)
            """)
    int insert(@Param("row") AgeVerificationRow row);

    /** 最新一次核验记录（不论结果；仅本人）。 */
    @Select("""
            SELECT * FROM age_verification WHERE user_id = #{userId}
            ORDER BY verified_at DESC, id DESC LIMIT 1
            """)
    AgeVerificationRow findLatest(@Param("userId") String userId);

    /** 当前仍有效的 VERIFIED 记录（到期即失效；/me 读取派生用）。 */
    @Select("""
            SELECT * FROM age_verification WHERE user_id = #{userId}
                AND result = 'VERIFIED' AND expires_at > #{now}
            ORDER BY verified_at DESC, id DESC LIMIT 1
            """)
    AgeVerificationRow findLatestValid(@Param("userId") String userId,
                                       @Param("now") java.time.LocalDateTime now);
}
