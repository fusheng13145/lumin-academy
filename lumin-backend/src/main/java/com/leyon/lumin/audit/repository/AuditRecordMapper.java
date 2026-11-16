package com.leyon.lumin.audit.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** audit_record 表数据访问（04 §5.1：append-only，仅追加无更新/删除）。 */
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
}
