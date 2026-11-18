package com.leyon.lumin.moderation.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** machine_report 表数据访问（04 §5；grade 配对与预算约束由 CHECK 兜底）。 */
@Mapper
public interface MachineReportMapper {

    @Insert("""
            INSERT INTO machine_report (id, case_id, provider, model_version,
                material_sha256, grade, policy_version, risk, flags, suggested_decision,
                confidence, evidence_refs, input_tokens, output_tokens, duration_ms,
                completed_at, created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.caseId}, #{row.provider}, #{row.modelVersion},
                #{row.materialSha256}, #{row.grade}, #{row.policyVersion}, #{row.risk},
                #{row.flags}, #{row.suggestedDecision}, #{row.confidence},
                #{row.evidenceRefs}, #{row.inputTokens}, #{row.outputTokens},
                #{row.durationMs}, #{row.completedAt}, #{row.createdAt}, #{row.updatedAt},
                #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") MachineReportRow row);
}
