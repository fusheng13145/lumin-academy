package com.leyon.lumin.ai.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AiCandidateMapper {

    @Insert("""
            INSERT INTO ai_candidate (id, run_id, content, source, state, created_at)
            VALUES (#{id}, #{runId}, #{content}, #{source}, #{state}, #{createdAt})
            """)
    int insert(AiCandidateRow row);

    @Update("""
            UPDATE ai_candidate
               SET state = #{state}
             WHERE id = #{id}
               AND run_id = #{runId}
            """)
    int updateState(@Param("id") String id, @Param("runId") String runId, @Param("state") String state);

    @Select("""
            SELECT id, run_id, content, source, state, created_at
              FROM ai_candidate
             WHERE run_id = #{runId}
             ORDER BY created_at ASC
            """)
    List<AiCandidateRow> findByRunId(@Param("runId") String runId);
}
