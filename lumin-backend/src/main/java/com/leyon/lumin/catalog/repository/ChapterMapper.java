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

    /** 作者作品的章节列表（05 §3.2 listMyChapters：含可编辑草稿状态）。 */
    @Select("""
            SELECT c.id, c.sequence_no, c.business_state,
                   d.id AS draft_id, d.state AS draft_state, d.title AS draft_title,
                   d.lock_version AS draft_lock_version
            FROM chapter c
            LEFT JOIN draft d ON d.chapter_id = c.id AND d.state = 'EDITABLE'
                AND d.deleted_at IS NULL
            WHERE c.work_id = #{workId} AND c.deleted_at IS NULL
            ORDER BY c.sequence_no
            """)
    java.util.List<ChapterListRow> listByWorkWithDraft(@Param("workId") String workId);
}
