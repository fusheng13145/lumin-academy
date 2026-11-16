package com.leyon.lumin.catalog.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** chapter 表数据访问（04 §4；归属校验供 authoring 经 catalog 服务端口使用）。 */
@Mapper
public interface ChapterMapper {

    @Select("SELECT work_id FROM chapter WHERE id = #{id} AND deleted_at IS NULL")
    String findWorkIdById(@Param("id") String id);

    @Insert("""
            INSERT INTO chapter (id, work_id, sequence_no, business_state,
                created_at, updated_at, created_by, updated_by)
            VALUES (#{id}, #{workId}, #{sequenceNo}, 'DRAFT', #{now}, #{now},
                #{operator}, #{operator})
            """)
    int insert(@Param("id") String id, @Param("workId") String workId,
               @Param("sequenceNo") int sequenceNo, @Param("operator") String operator,
               @Param("now") java.time.LocalDateTime now);
}
