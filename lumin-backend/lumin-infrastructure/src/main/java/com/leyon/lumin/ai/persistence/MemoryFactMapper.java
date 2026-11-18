package com.leyon.lumin.ai.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MemoryFactMapper {

    @Select("""
            SELECT id, work_id, kind, subject_key, content, status, confidence, source_version,
                   created_at, updated_at
              FROM memory_fact
             WHERE work_id = #{workId}
               AND status = 'ACTIVE'
               AND deleted_at IS NULL
             ORDER BY updated_at DESC
            """)
    List<MemoryFactRow> findActive(@Param("workId") String workId);

    @Select("""
            SELECT id, work_id, kind, subject_key, content, status, confidence, source_version,
                   created_at, updated_at
              FROM memory_fact
             WHERE work_id = #{workId}
               AND deleted_at IS NULL
             ORDER BY updated_at DESC
            """)
    List<MemoryFactRow> findByWorkId(@Param("workId") String workId);

    @Select("""
            SELECT id, work_id, kind, subject_key, content, status, confidence, source_version,
                   created_at, updated_at
              FROM memory_fact
             WHERE id = #{factId}
            """)
    MemoryFactRow findById(@Param("factId") String factId);

    @Insert("""
            INSERT INTO memory_fact (id, work_id, kind, subject_key, content, status, confidence,
                                     source_version, created_at, updated_at)
            VALUES (#{id}, #{workId}, #{kind}, #{subjectKey}, #{content}, #{status}, #{confidence},
                    #{sourceVersion}, #{createdAt}, #{updatedAt})
            """)
    int insertFact(MemoryFactRow row);

    @Update("""
            UPDATE memory_fact
               SET status = #{status},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int updateStatus(@Param("id") String id, @Param("status") String status, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    @Insert("""
            INSERT INTO memory_event (id, work_id, fact_id, event_type, before_json, after_json, actor_id, created_at)
            VALUES (#{id}, #{workId}, #{factId}, #{eventType}, #{beforeJson}, #{afterJson}, #{actorId}, #{createdAt})
            """)
    int insertEvent(MemoryEventRow row);
}
