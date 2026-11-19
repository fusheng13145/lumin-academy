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

    /** 可见限制列表（05 §3.3 listRestrictions）：先授权过滤再分页。 */
    @Select("""
            <script>
            SELECT r.* FROM restriction r
            WHERE r.deleted_at IS NULL
            <choose>
              <when test="queueViewer">
                AND 1 = 1
              </when>
              <otherwise>
                AND (r.requested_by = #{subject}
                     OR EXISTS (SELECT 1 FROM work w
                                WHERE w.id = r.work_id AND w.deleted_at IS NULL
                                  AND w.author_id = #{subject}))
              </otherwise>
            </choose>
            <if test="cursorAt != null">
              AND (r.created_at, r.id) &lt; (#{cursorAt,jdbcType=TIMESTAMP}, #{cursorId,jdbcType=VARCHAR})
            </if>
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT #{limit}
            </script>
            """)
    java.util.List<RestrictionRow> listVisible(@Param("subject") String subject,
                                               @Param("queueViewer") boolean queueViewer,
                                               @Param("cursorAt") java.time.LocalDateTime cursorAt,
                                               @Param("cursorId") String cursorId,
                                               @Param("limit") int limit);

    /** 目标当前生效限制（含父作品覆盖全部章节，INV-03）。 */
    @Select("""
            SELECT DISTINCT kind FROM restriction
            WHERE state = 'ACTIVE' AND deleted_at IS NULL
              AND (expires_at IS NULL OR expires_at > #{now})
              AND ((target_scope = 'WORK' AND work_id = #{workId})
                   OR (target_scope = 'CHAPTER' AND chapter_id = #{chapterId}))
            """)
    java.util.List<String> selectActiveKinds(@Param("workId") String workId,
                                             @Param("chapterId") String chapterId,
                                             @Param("now") java.time.LocalDateTime now);

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
