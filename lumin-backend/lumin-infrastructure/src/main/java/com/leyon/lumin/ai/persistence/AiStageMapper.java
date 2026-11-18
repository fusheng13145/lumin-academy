package com.leyon.lumin.ai.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AiStageMapper {

    @Insert("""
            INSERT INTO ai_run_stage (id, run_id, stage_type, attempt, state, output, error_message,
                                      started_at, finished_at)
            VALUES (#{id}, #{runId}, #{stageType}, #{attempt}, #{state}, #{output}, #{errorMessage},
                    #{startedAt}, #{finishedAt})
            """)
    int insert(AiStageRow row);

    @Update("""
            UPDATE ai_run_stage
               SET state = #{state},
                   output = #{output},
                   error_message = #{errorMessage},
                   finished_at = #{finishedAt}
             WHERE id = #{id}
            """)
    int update(AiStageRow row);

    @Select("""
            SELECT id, run_id, stage_type, attempt, state, output, error_message, started_at, finished_at
              FROM ai_run_stage
             WHERE run_id = #{runId}
             ORDER BY started_at ASC, attempt ASC, id ASC
            """)
    List<AiStageRow> findByRunId(@Param("runId") String runId);
}
