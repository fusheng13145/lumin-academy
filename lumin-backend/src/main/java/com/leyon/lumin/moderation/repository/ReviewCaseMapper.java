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

    /** 机审报告到达后按 N4 路由更新风险与 SLA 优先级（03 §2.4）。 */
    @Update("""
            UPDATE review_case SET risk = #{risk}, priority = #{priority},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id}
            """)
    int updateRiskAndPriority(@Param("id") String id, @Param("risk") String risk,
                              @Param("priority") String priority,
                              @Param("operator") String operator,
                              @Param("now") java.time.LocalDateTime now);

    /** 案件状态迁移持久化（ReviewCaseStateMachine 裁决后调用）。 */
    @Update("""
            UPDATE review_case SET state = #{state}, updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id}
            """)
    int updateState(@Param("id") String id, @Param("state") String state,
                    @Param("operator") String operator,
                    @Param("now") java.time.LocalDateTime now);
}
