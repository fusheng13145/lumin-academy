package com.leyon.lumin.governance.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** restriction 表数据访问（04 §5；状态迁移经 RestrictionStateMachine 裁决后调用）。 */
@Mapper
public interface RestrictionMapper {

    @Insert("""
            INSERT INTO restriction (id, work_id, chapter_id, kind, state, reason, case_id,
                requested_by, reviewed_by, effective_at, expires_at, revoked_at, appeal_id,
                created_at, updated_at, created_by, updated_by)
            VALUES (#{row.id}, #{row.workId}, #{row.chapterId}, #{row.kind}, #{row.state},
                #{row.reason}, #{row.caseId}, #{row.requestedBy}, #{row.reviewedBy},
                #{row.effectiveAt}, #{row.expiresAt}, #{row.revokedAt}, #{row.appealId},
                #{row.createdAt}, #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy})
            """)
    int insert(@Param("row") RestrictionRow row);

    @Select("SELECT * FROM restriction WHERE id = #{id} AND deleted_at IS NULL")
    RestrictionRow findById(@Param("id") String id);

    /** 目标当前生效限制（含父作品覆盖全部章节，INV-03）。 */
    @Select("""
            SELECT DISTINCT kind FROM restriction
            WHERE state = 'ACTIVE' AND deleted_at IS NULL
              AND ((target_scope = 'WORK' AND work_id = #{workId})
                   OR (target_scope = 'CHAPTER' AND chapter_id = #{chapterId}))
            """)
    java.util.List<String> selectActiveKinds(@Param("workId") String workId,
                                             @Param("chapterId") String chapterId);

    /** 状态迁移持久化（RestrictionStateMachine 裁决后调用；REVOKED 记 revoked_at）。 */
    @Update("""
            UPDATE restriction SET state = #{state}, reviewed_by = #{reviewer},
                revoked_at = #{revokedAt}, updated_at = #{now}, updated_by = #{operator},
                lock_version = lock_version + 1
            WHERE id = #{id} AND state = #{expectedState}
                AND lock_version = #{expectedLockVersion}
            """)
    int transitionState(@Param("id") String id, @Param("state") String state,
                        @Param("reviewer") String reviewer,
                        @Param("revokedAt") java.time.LocalDateTime revokedAt,
                        @Param("expectedState") String expectedState,
                        @Param("operator") String operator,
                        @Param("now") java.time.LocalDateTime now,
                        @Param("expectedLockVersion") long expectedLockVersion);
}
