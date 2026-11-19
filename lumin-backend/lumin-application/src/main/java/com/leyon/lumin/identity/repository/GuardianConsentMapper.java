package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** guardian_consent 表数据访问（docs/04 §3；仅同意双方可见，管理员不例外）。 */
@Mapper
public interface GuardianConsentMapper {

    @Insert("""
            INSERT INTO guardian_consent (id, guardian_id, child_id, proof_digest,
                proof_ref_cipher, scope, granted_at, revoked_at, created_at, updated_at,
                created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.guardianId}, #{row.childId}, #{row.proofDigest},
                #{row.proofRefCipher}, #{row.scope}, #{row.grantedAt}, #{row.revokedAt},
                #{row.createdAt}, #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy}, 1)
            """)
    int insert(@Param("row") GuardianConsentRow row);

    @Select("SELECT * FROM guardian_consent WHERE id = #{id}")
    GuardianConsentRow findById(@Param("id") String id);

    @Select("SELECT * FROM guardian_consent WHERE proof_digest = #{proofDigest} LIMIT 1")
    GuardianConsentRow findByProofDigest(@Param("proofDigest") String proofDigest);

    /** 本人参与（监护人本人或 childId 本人）先过滤后分页；不向管理员开放。 */
    @Select("""
            <script>
            SELECT * FROM guardian_consent
            WHERE (guardian_id = #{subject} OR child_id = #{subject})
            <if test="cursorAt != null">
                AND (granted_at &lt; #{cursorAt} OR (granted_at = #{cursorAt} AND id &lt; #{cursorId}))
            </if>
            ORDER BY granted_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<GuardianConsentRow> listVisible(@Param("subject") String subject,
                                         @Param("cursorAt") LocalDateTime cursorAt,
                                         @Param("cursorId") String cursorId,
                                         @Param("limit") int limit);

    /** 仅同意监护人可撤销；乐观锁 + 未撤销条件，返回 0 表示并发/状态不符。 */
    @Update("""
            UPDATE guardian_consent SET revoked_at = #{now}, updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND guardian_id = #{guardian} AND revoked_at IS NULL
                AND lock_version = #{expectedRevision}
            """)
    int revoke(@Param("id") String id, @Param("guardian") String guardian,
               @Param("expectedRevision") long expectedRevision,
               @Param("now") LocalDateTime now, @Param("operator") String operator);
}
