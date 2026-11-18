package com.leyon.lumin.governance.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** legal_hold 表数据访问（docs/04 §5.2 governance；所有保全读取留痕由调用方审计）。 */
@Mapper
public interface LegalHoldMapper {

    @Insert("""
            INSERT INTO legal_hold (id, subject_type, subject_id, basis_ref_cipher, state,
                expires_at, owner_id, reason, released_at, created_at, updated_at,
                created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.subjectType}, #{row.subjectId}, #{row.basisRefCipher},
                #{row.state}, #{row.expiresAt}, #{row.ownerId}, #{row.reason},
                #{row.releasedAt}, #{row.createdAt}, #{row.updatedAt}, #{row.createdBy},
                #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") LegalHoldRow row);

    @Select("""
            SELECT * FROM legal_hold WHERE subject_type = #{subjectType}
                AND subject_id = #{subjectId} AND state = 'ACTIVE'
            """)
    List<LegalHoldRow> findActiveBySubject(@Param("subjectType") String subjectType,
                                           @Param("subjectId") String subjectId);

    @Select("SELECT * FROM legal_hold WHERE id = #{id} FOR UPDATE")
    LegalHoldRow findByIdForUpdate(@Param("id") String id);

    /** 保全释放（ACTIVE → RELEASED；乐观锁）。 */
    @Update("""
            UPDATE legal_hold SET state = 'RELEASED', released_at = #{releasedAt},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND state = 'ACTIVE' AND lock_version = #{expectedLockVersion}
            """)
    int release(@Param("id") String id, @Param("releasedAt") LocalDateTime releasedAt,
                @Param("operator") String operator, @Param("now") LocalDateTime now,
                @Param("expectedLockVersion") long expectedLockVersion);
}
