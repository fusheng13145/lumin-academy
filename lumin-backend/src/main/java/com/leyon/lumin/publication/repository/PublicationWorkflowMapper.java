package com.leyon.lumin.publication.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** publication_workflow 表数据访问（03 §2.3 发布流程迁移由 PublicationStateMachine 裁决后调用）。 */
@Mapper
public interface PublicationWorkflowMapper {

    @Insert("""
            INSERT INTO publication_workflow (id, version_id, target_type, target_id, state,
                policy_version, case_id, suspended_reason, created_at, updated_at,
                created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.versionId}, #{row.targetType}, #{row.targetId},
                #{row.state}, #{row.policyVersion}, #{row.caseId}, #{row.suspendedReason},
                #{row.createdAt}, #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy},
                #{row.lockVersion})
            """)
    int insert(@Param("row") PublicationWorkflowRow row);

    @Select("SELECT * FROM publication_workflow WHERE id = #{id}")
    PublicationWorkflowRow findById(@Param("id") String id);

    /** 发布事务锁行（03 §4.2：锁目标与 workflow 行）。 */
    @Select("SELECT * FROM publication_workflow WHERE id = #{id} FOR UPDATE")
    PublicationWorkflowRow findByIdForUpdate(@Param("id") String id);

    /** 状态 + 挂起原因一并更新（SUSPENDED⇔原因一致性由 CHECK 兜底）。 */
    @Update("""
            UPDATE publication_workflow SET state = #{state}, suspended_reason = #{reason},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id}
            """)
    int updateState(@Param("id") String id, @Param("state") String state,
                    @Param("reason") String reason, @Param("operator") String operator,
                    @Param("now") java.time.LocalDateTime now);

    /** 同目标未结束流程计数（03 §2.2：同目标不得有另一个未结束流程；open_slot 同口径）。 */
    @Select("""
            SELECT COUNT(*) FROM publication_workflow
            WHERE target_type = #{targetType} AND target_id = #{targetId}
              AND state IN ('SUBMITTED','AUTO_REVIEW','HUMAN_REVIEW','SUSPENDED')
            """)
    int countOpenByTarget(@Param("targetType") String targetType,
                          @Param("targetId") String targetId);

    /** 建案后回填案件关联并推进状态（状态机裁决由服务层先行，03 §2.3 AUTO_TASK_CLAIMED）。 */
    @Update("""
            UPDATE publication_workflow SET state = #{state}, case_id = #{caseId},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id}
            """)
    int updateStateAndCase(@Param("id") String id, @Param("state") String state,
                           @Param("caseId") String caseId, @Param("operator") String operator,
                           @Param("now") java.time.LocalDateTime now);

    /** 按版本查流程（INV-01：一份不可变版本只绑定一个发布流程）。 */
    @Select("SELECT * FROM publication_workflow WHERE version_id = #{versionId}")
    PublicationWorkflowRow findByVersionId(@Param("versionId") String versionId);
}
