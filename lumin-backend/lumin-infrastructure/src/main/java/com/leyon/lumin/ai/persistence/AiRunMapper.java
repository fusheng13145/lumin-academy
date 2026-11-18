package com.leyon.lumin.ai.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiRunMapper {

    @Insert("""
            INSERT INTO ai_run (id, work_id, chapter_id, mode, state, instruction, provider, model,
                                memory_revision, error_message, created_at, updated_at)
            VALUES (#{id}, #{workId}, #{chapterId}, #{mode}, #{state}, #{instruction}, #{provider}, #{model},
                    #{memoryRevision}, #{errorMessage}, #{createdAt}, #{updatedAt})
            """)
    int insert(AiRunRow row);

    @Update("""
            UPDATE ai_run
               SET state = #{state},
                   error_message = #{errorMessage},
                   updated_at = #{updatedAt}
             WHERE id = #{id}
            """)
    int update(AiRunRow row);

    @Select("""
            SELECT id, work_id, chapter_id, mode, state, instruction, provider, model,
                   memory_revision, error_message, created_at, updated_at
              FROM ai_run
             WHERE id = #{runId}
            """)
    AiRunRow findById(@Param("runId") String runId);
}
