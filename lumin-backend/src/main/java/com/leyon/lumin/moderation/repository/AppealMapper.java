package com.leyon.lumin.moderation.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** appeal 表数据访问（04 §5；OPEN→IN_REVIEW→终态由 AppealStateMachine 裁决后调用）。 */
@Mapper
public interface AppealMapper {

    @Insert("""
            INSERT INTO appeal (id, case_id, applicant_id, reason, evidence_refs,
                reviewer_id, state, decision_reason, accepted_due_at, completed_due_at,
                created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.caseId}, #{row.applicantId}, #{row.reason},
                #{row.evidenceRefs}, #{row.reviewerId}, #{row.state}, #{row.decisionReason},
                #{row.acceptedDueAt}, #{row.completedDueAt}, #{row.createdAt},
                #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") AppealRow row);

    @Select("SELECT * FROM appeal WHERE id = #{id}")
    AppealRow findById(@Param("id") String id);

    @Select("""
            SELECT * FROM appeal WHERE case_id = #{caseId}
              AND state IN ('OPEN','IN_REVIEW')
            """)
    AppealRow findOpenByCaseId(@Param("caseId") String caseId);

    /** 复核迁移持久化（乐观锁兜底并发）。 */
    @Update("""
            UPDATE appeal SET state = #{state}, reviewer_id = #{reviewerId},
                decision_reason = #{decisionReason}, updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND state = #{expectedState}
                AND lock_version = #{expectedLockVersion}
            """)
    int transition(@Param("id") String id, @Param("state") String state,
                   @Param("reviewerId") String reviewerId,
                   @Param("decisionReason") String decisionReason,
                   @Param("expectedState") String expectedState,
                   @Param("operator") String operator, @Param("now") java.time.LocalDateTime now,
                   @Param("expectedLockVersion") long expectedLockVersion);
}
