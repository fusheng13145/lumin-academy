package com.leyon.lumin.moderation.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** review_decision 表数据访问（04 §5：append-only；同案件独立双审主体约束由事务再校验）。 */
@Mapper
public interface ReviewDecisionMapper {

    @Insert("""
            INSERT INTO review_decision (id, case_id, actor_id, actor_type, decision,
                grade, reason_code, reason, evidence_refs, stage, policy_version,
                created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.caseId}, #{row.actorId}, #{row.actorType},
                #{row.decision}, #{row.grade}, #{row.reasonCode}, #{row.reason},
                #{row.evidenceRefs}, #{row.stage}, #{row.policyVersion},
                #{row.createdAt}, #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy},
                #{row.lockVersion})
            """)
    int insert(@Param("row") ReviewDecisionRow row);
}
