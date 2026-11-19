package com.leyon.lumin.moderation.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
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

    /** 原案件全部裁决记录（阶段 3 回避集合 + 二审比对，03 §2.4/§6）。 */
    @Select("SELECT * FROM review_decision WHERE case_id = #{caseId} ORDER BY created_at, id")
    java.util.List<ReviewDecisionRow> findByCase(@Param("caseId") String caseId);

    /** 队列列表批量读取，避免逐案件 N+1（增量四十九）。 */
    @Select("""
            <script>
            SELECT * FROM review_decision
            WHERE case_id IN
            <foreach collection="caseIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY created_at, id
            </script>
            """)
    java.util.List<ReviewDecisionRow> findByCaseIds(@Param("caseIds") java.util.List<String> caseIds);
}
