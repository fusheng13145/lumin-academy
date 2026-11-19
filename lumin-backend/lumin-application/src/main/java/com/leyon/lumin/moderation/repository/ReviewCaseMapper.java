package com.leyon.lumin.moderation.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** review_case 表数据访问（04 §5；案件状态迁移由 ReviewCaseStateMachine 裁决后调用）。 */
@Mapper
public interface ReviewCaseMapper {

    @Insert("""
            INSERT INTO review_case (id, version_id, workflow_id, work_id, chapter_id,
                source, risk, priority, state, queue_id, review_stage, assignee_id,
                lease_expires_at, response_due_at, completion_due_at, policy_version,
                created_at, updated_at, created_by, updated_by)
            VALUES (#{row.id}, #{row.versionId}, #{row.workflowId}, #{row.workId},
                #{row.chapterId}, #{row.source}, #{row.risk}, #{row.priority},
                #{row.state}, #{row.queueId}, #{row.reviewStage}, #{row.assigneeId},
                #{row.leaseExpiresAt}, #{row.responseDueAt}, #{row.completionDueAt},
                #{row.policyVersion}, #{row.createdAt}, #{row.updatedAt},
                #{row.createdBy}, #{row.updatedBy})
            """)
    int insert(@Param("row") ReviewCaseRow row);

    @Select("SELECT * FROM review_case WHERE id = #{id} AND deleted_at IS NULL")
    ReviewCaseRow findById(@Param("id") String id);

    /** 领取/裁决锁行（03 §2.4：领取在同一事务核验阶段、回避集合和租约）。 */
    @Select("SELECT * FROM review_case WHERE id = #{id} AND deleted_at IS NULL FOR UPDATE")
    ReviewCaseRow findByIdForUpdate(@Param("id") String id);

    /** 领取迁移持久化：state/assignee/租约一并更新（乐观锁兜底并发领取，T20）。 */
    @Update("""
            UPDATE review_case SET state = #{state}, assignee_id = #{assigneeId},
                lease_expires_at = #{leaseExpiresAt}, updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND state IN ('QUEUED','WAITING_SECOND')
                AND lock_version = #{expectedLockVersion}
            """)
    int claim(@Param("id") String id, @Param("state") String state,
              @Param("assigneeId") String assigneeId,
              @Param("leaseExpiresAt") java.time.LocalDateTime leaseExpiresAt,
              @Param("operator") String operator, @Param("now") java.time.LocalDateTime now,
              @Param("expectedLockVersion") long expectedLockVersion);

    /** 案件状态迁移持久化（期望状态守卫 + 乐观锁兜底并发）。 */
    @Update("""
            UPDATE review_case SET state = #{state}, review_stage = #{stage},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND state = #{expectedState}
                AND lock_version = #{expectedLockVersion}
            """)
    int transitionState(@Param("id") String id, @Param("state") String state,
                        @Param("stage") int stage,
                        @Param("expectedState") String expectedState,
                        @Param("operator") String operator,
                        @Param("now") java.time.LocalDateTime now,
                        @Param("expectedLockVersion") long expectedLockVersion);

    /** 机审报告到达后按 N4 路由更新风险与 SLA 优先级（元数据更新，不递增业务乐观锁）。 */
    @Update("""
            UPDATE review_case SET risk = #{risk}, priority = #{priority},
                updated_at = #{now}, updated_by = #{operator}
            WHERE id = #{id}
            """)
    int updateRiskAndPriority(@Param("id") String id, @Param("risk") String risk,
                              @Param("priority") String priority,
                              @Param("operator") String operator,
                              @Param("now") java.time.LocalDateTime now);

    /** 队列列表（03 §2.4：priority/completion_due_at/id 排序；不含正文；签名游标）。 */
    @Select("""
            SELECT * FROM review_case
            WHERE state IN ('QUEUED','WAITING_SECOND') AND deleted_at IS NULL
              AND (FIELD(priority,'P0','P1','P2','P3') > #{cursorRank}
                   OR (FIELD(priority,'P0','P1','P2','P3') = #{cursorRank}
                       AND (completion_due_at > #{cursorDue}
                            OR (completion_due_at = #{cursorDue} AND id > #{cursorId}))))
            ORDER BY FIELD(priority,'P0','P1','P2','P3'), completion_due_at, id
            LIMIT #{limit}
            """)
    java.util.List<ReviewCaseRow> listQueued(@Param("cursorRank") int cursorRank,
                                             @Param("cursorDue") java.time.LocalDateTime cursorDue,
                                             @Param("cursorId") String cursorId,
                                             @Param("limit") int limit);
}
