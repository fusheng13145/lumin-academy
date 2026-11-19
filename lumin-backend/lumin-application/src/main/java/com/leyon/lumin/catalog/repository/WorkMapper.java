package com.leyon.lumin.catalog.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** work 表数据访问（04 §4；业务态迁移由 PublicationStateMachine 裁决后调用）。 */
@Mapper
public interface WorkMapper {

    @Insert("""
            INSERT INTO work (id, author_id, business_state, copyright_status,
                next_chapter_no, created_at, updated_at, created_by, updated_by)
            VALUES (#{row.id}, #{row.authorId}, #{row.businessState}, #{row.copyrightStatus},
                #{row.nextChapterNo}, #{row.createdAt}, #{row.updatedAt},
                #{row.createdBy}, #{row.updatedBy})
            """)
    int insert(@Param("row") WorkRow row);

    @Select("SELECT * FROM work WHERE id = #{id} AND deleted_at IS NULL")
    WorkRow findById(@Param("id") String id);

    /** 队列页作者年龄带批量组装，避免逐作品 N+1（增量五十）。 */
    @Select("""
            <script>
            SELECT * FROM work WHERE deleted_at IS NULL AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    java.util.List<WorkRow> findByIds(@Param("ids") java.util.List<String> ids);

    /**
     * 原子取章节序号（03 §4.2：同作品新增章节序号使用唯一约束；完结/下架/终止
     * 作品禁止新章——business_state 白名单兜底；乐观锁 lock_version 兜底并发）。
     */
    @Update("""
            UPDATE work SET next_chapter_no = next_chapter_no + 1,
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND lock_version = #{expectedLockVersion}
                AND business_state IN ('DRAFT','ACTIVE') AND deleted_at IS NULL
            """)
    int allocateChapterNo(@Param("id") String id,
                          @Param("expectedLockVersion") long expectedLockVersion,
                          @Param("operator") String operator,
                          @Param("now") java.time.LocalDateTime now);

    @Select("SELECT next_chapter_no FROM work WHERE id = #{id}")
    Integer selectNextChapterNo(@Param("id") String id);

    /** 业务态迁移持久化（PublicationStateMachine.business 裁决后调用；03 §2.1）。 */
    @Update("""
            UPDATE work SET business_state = #{state}, prior_state = #{priorState},
                completed_at = #{completedAt}, updated_at = #{now}, updated_by = #{operator},
                lock_version = lock_version + 1
            WHERE id = #{id} AND business_state = #{expectedState}
            """)
    int updateBusinessState(@Param("id") String id, @Param("state") String state,
                            @Param("priorState") String priorState,
                            @Param("completedAt") java.time.LocalDateTime completedAt,
                            @Param("operator") String operator,
                            @Param("now") java.time.LocalDateTime now,
                            @Param("expectedState") String expectedState);

    /** 作者私有作品列表（05 §3.2 listMyWorks：含 DRAFT 与可编辑草稿状态；created_at,id 倒序游标）。 */
    @Select("""
            SELECT w.id, w.business_state, w.copyright_status, w.lock_version,
                   d.id AS draft_id, d.state AS draft_state, d.title AS draft_title,
                   d.lock_version AS draft_lock_version,
                   cv.title AS latest_title, cv.state AS latest_version_state,
                   cv.grade AS latest_grade,
                   p.version_id AS published_version_id, w.created_at
            FROM work w
            LEFT JOIN draft d ON d.work_id = w.id AND d.state = 'EDITABLE'
                AND d.deleted_at IS NULL
            LEFT JOIN content_version cv ON cv.id = (SELECT c.id FROM content_version c
                WHERE c.work_id = w.id ORDER BY c.revision_no DESC LIMIT 1)
            LEFT JOIN publication_pointer p ON p.target_type = 'WORK' AND p.target_id = w.id
            WHERE w.author_id = #{authorId} AND w.deleted_at IS NULL
              AND (w.created_at < #{cursorAt}
                   OR (w.created_at = #{cursorAt} AND w.id < #{cursorId}))
            ORDER BY w.created_at DESC, w.id DESC
            LIMIT #{limit}
            """)
    java.util.List<MyWorkRow> listMyWorks(@Param("authorId") String authorId,
                                          @Param("cursorAt") java.time.LocalDateTime cursorAt,
                                          @Param("cursorId") String cursorId,
                                          @Param("limit") int limit);

    /** 作者私有作品详情（05 §3.2 getMyWork：不存在/越权由服务层统一 404）。 */
    @Select("""
            SELECT w.id, w.business_state, w.lock_version,
                   cv.title,
                   d.id AS draft_id, d.state AS draft_state,
                   p.version_id AS published_version_id
            FROM work w
            LEFT JOIN content_version cv ON cv.id = (SELECT c.id FROM content_version c
                WHERE c.work_id = w.id AND c.target_type = 'WORK'
                ORDER BY c.revision_no DESC LIMIT 1)
            LEFT JOIN draft d ON d.id = (SELECT c.id FROM draft c
                WHERE c.work_id = w.id AND c.target_type = 'WORK' AND c.state = 'EDITABLE'
                    AND c.deleted_at IS NULL
                ORDER BY c.updated_at DESC, c.id DESC LIMIT 1)
            LEFT JOIN publication_pointer p ON p.target_type = 'WORK' AND p.target_id = w.id
            WHERE w.id = #{workId} AND w.author_id = #{authorId} AND w.deleted_at IS NULL
            LIMIT 1
            """)
    MyWorkDetailRow findMyWorkDetail(@Param("workId") String workId,
                                     @Param("authorId") String authorId);

    /** 业务态迁移并校验乐观锁（作者生命周期动作；行数 0 区分并发/非法迁移）。 */
    @Update("""
            UPDATE work SET business_state = #{state}, prior_state = #{priorState},
                completed_at = #{completedAt}, updated_at = #{now}, updated_by = #{operator},
                lock_version = lock_version + 1
            WHERE id = #{id} AND business_state = #{expectedState}
                AND lock_version = #{expectedLockVersion} AND deleted_at IS NULL
            """)
    int updateBusinessStateIfLock(@Param("id") String id, @Param("state") String state,
                                  @Param("priorState") String priorState,
                                  @Param("completedAt") java.time.LocalDateTime completedAt,
                                  @Param("operator") String operator,
                                  @Param("now") java.time.LocalDateTime now,
                                  @Param("expectedState") String expectedState,
                                  @Param("expectedLockVersion") long expectedLockVersion);
}