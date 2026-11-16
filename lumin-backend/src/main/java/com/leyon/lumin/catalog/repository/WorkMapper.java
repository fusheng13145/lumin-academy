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
}
