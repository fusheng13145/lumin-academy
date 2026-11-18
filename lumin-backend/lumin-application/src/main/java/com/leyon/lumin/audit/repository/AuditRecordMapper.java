package com.leyon.lumin.audit.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** audit_record 表数据访问（04 §5.1：append-only，仅追加无更新/删除；读取 AUDITOR 作用域）。 */
@Mapper
public interface AuditRecordMapper {

    @Insert("""
            INSERT INTO audit_record (id, actor_id, role_context, action, resource_type,
                resource_id, before_redacted, after_redacted, reason, trace_id, event_at,
                digest, created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.actorId}, #{row.roleContext}, #{row.action},
                #{row.resourceType}, #{row.resourceId}, #{row.beforeRedacted},
                #{row.afterRedacted}, #{row.reason}, #{row.traceId}, #{row.eventAt},
                #{row.digest}, #{row.createdAt}, #{row.updatedAt}, #{row.createdBy},
                #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") AuditRecordRow row);

    /** 审查读取（只读；先过滤后 limit，时间倒序；05 §3.4 listAuditRecords）。 */
    @Select("""
            <script>
            SELECT * FROM audit_record
            <where>
                <if test="resourceType != null">AND resource_type = #{resourceType}</if>
                <if test="resourceId != null">AND resource_id = #{resourceId}</if>
                <if test="actorId != null">AND actor_id = #{actorId}</if>
                <if test="action != null">AND action = #{action}</if>
            </where>
            ORDER BY event_at DESC, id LIMIT #{limit}
            </script>
            """)
    List<AuditRecordRow> search(@Param("resourceType") String resourceType,
                                @Param("resourceId") String resourceId,
                                @Param("actorId") String actorId,
                                @Param("action") String action,
                                @Param("limit") int limit);
}
