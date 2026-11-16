package com.leyon.lumin.publication.persistence;

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

    /** 状态 + 挂起原因一并更新（SUSPENDED⇔原因一致性由 CHECK 兜底）。 */
    @Update("""
            UPDATE publication_workflow SET state = #{state}, suspended_reason = #{reason},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id}
            """)
    int updateState(@Param("id") String id, @Param("state") String state,
                    @Param("reason") String reason, @Param("operator") String operator,
                    @Param("now") java.time.LocalDateTime now);
}
