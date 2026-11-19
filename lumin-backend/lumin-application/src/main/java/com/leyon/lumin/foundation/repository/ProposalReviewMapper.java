package com.leyon.lumin.foundation.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** proposal_review 数据访问（docs/04 §5.2；各提案域按 resource_type 归属）。 */
@Mapper
public interface ProposalReviewMapper {

    @Insert("""
            INSERT INTO proposal_review (id, resource_type, resource_id, actor_id, action,
                reason, evidence_refs, reviewed_revision, event_at, created_at, updated_at,
                created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.resourceType}, #{row.resourceId}, #{row.actorId},
                #{row.action}, #{row.reason}, #{row.evidenceRefsJson},
                #{row.reviewedRevision}, #{row.eventAt}, #{row.createdAt}, #{row.updatedAt},
                #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") ProposalReviewRow row);

    /** 完整审批记录（按发生顺序；空列表表示尚未审查，不截断历史）。 */
    @Select("""
            SELECT * FROM proposal_review
            WHERE resource_type = #{resourceType} AND resource_id = #{resourceId}
            ORDER BY event_at, id
            """)
    List<ProposalReviewRow> listByResource(@Param("resourceType") String resourceType,
                                           @Param("resourceId") String resourceId);
}
