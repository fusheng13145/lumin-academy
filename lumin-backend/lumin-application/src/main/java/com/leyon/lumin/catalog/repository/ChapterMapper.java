package com.leyon.lumin.catalog.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Select("SELECT id, business_state, prior_state FROM chapter WHERE id = #{id} AND deleted_at IS NULL")
    ChapterStateRow findStateById(@Param("id") String id);

    /** 作品下未达到 ACTIVE 的章节数（完结前“全部章节已发布”前提）。 */
    @Select("""
            SELECT COUNT(*) FROM chapter
            WHERE work_id = #{workId} AND deleted_at IS NULL AND business_state <> 'ACTIVE'
            """)
    int countUnpublishedByWork(@Param("workId") String workId);

    /** 首版发布业务态迁移（乐观锁 + 期望态兜底并发，与 WorkMapper.updateBusinessState 同式）。 */
    @Update("""
            UPDATE chapter SET business_state = #{state}, prior_state = #{priorState},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND business_state = #{expectedState}
            """)
    int updateBusinessState(@Param("id") String id, @Param("state") String state,
                            @Param("priorState") String priorState,
                            @Param("operator") String operator,
                            @Param("now") java.time.LocalDateTime now,
                            @Param("expectedState") String expectedState);

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
