package com.leyon.lumin.publication.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * publication_pointer 表数据访问（INV-02：仅指向同目标 APPROVED 版本；
 * 原子交换由服务层事务完成——先改行再校验，唯一约束兜底并发）。
 */
@Mapper
public interface PublicationPointerMapper {

    @Insert("""
            INSERT INTO publication_pointer (id, target_type, target_id, version_id,
                published_at, publication_revision, created_at, updated_at,
                created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.targetType}, #{row.targetId}, #{row.versionId},
                #{row.publishedAt}, #{row.publicationRevision}, #{row.createdAt},
                #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") PublicationPointerRow row);

    @Select("""
            SELECT * FROM publication_pointer
            WHERE target_type = #{targetType} AND target_id = #{targetId}
            """)
    PublicationPointerRow findByTarget(@Param("targetType") String targetType,
                                       @Param("targetId") String targetId);

    /** 原子交换：乐观锁 + 指针列更新（publication_revision 单调由服务层校验）。 */
    @Update("""
            UPDATE publication_pointer SET version_id = #{versionId},
                published_at = #{publishedAt}, publication_revision = #{publicationRevision},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND lock_version = #{expectedLockVersion}
            """)
    int swapVersion(@Param("id") String id, @Param("versionId") String versionId,
                    @Param("publishedAt") java.time.LocalDateTime publishedAt,
                    @Param("publicationRevision") long publicationRevision,
                    @Param("operator") String operator,
                    @Param("now") java.time.LocalDateTime now,
                    @Param("expectedLockVersion") long expectedLockVersion);

    /** 已发布作品列表（读者列表骨架；游标分页随 P1 批次，04 §7）。 */
    @Select("""
            SELECT p.* FROM publication_pointer p
            JOIN work w ON w.id = p.target_id AND p.target_type = 'WORK'
            WHERE w.business_state IN ('ACTIVE','COMPLETED') AND w.deleted_at IS NULL
            ORDER BY p.published_at DESC, p.id
            LIMIT #{limit}
            """)
    java.util.List<PublicationPointerRow> listPublishedWorks(@Param("limit") int limit);

    /** 读者作品列表行（UGC-009：授权过滤 SQL 化——等级口径见 ReaderService；
     *  字段顺序与 SELECT 列序一致）。 */
    record WorkFeedRow(
            java.time.LocalDateTime publishedAt,
            String pointerId,
            String versionId,
            String workId,
            String title,
            String author,
            String grade,
            String chapterId) {
    }

    /**
     * 读者授权 keyset 分页（UGC-009；05 §2 游标口径）：授权过滤 SQL 化——
     * G/PG_12 全带可见；PG_16 仅 bandVar∈(C2,C3)（C4 按 C1）；A 级默认关闭；
     * keyset (published_at, id) 稳定排序，跨页无漏重。
     */
    @Select("""
            SELECT p.published_at, p.id AS pointerId,
                   cv.id AS versionId, w.id AS workId, cv.title,
                   COALESCE(u.nickname, '') AS author, cv.grade, cv.chapter_id AS chapterId
            FROM publication_pointer p
            JOIN work w ON w.id = p.target_id AND p.target_type = 'WORK'
                AND w.business_state IN ('ACTIVE','COMPLETED') AND w.deleted_at IS NULL
            JOIN content_version cv ON cv.id = p.version_id AND cv.state = 'APPROVED'
            LEFT JOIN user_profile u ON u.id = w.author_id AND u.deleted_at IS NULL
            WHERE (cv.grade IN ('G','PG_12')
                OR (cv.grade = 'PG_16' AND #{bandVar} IN ('C2','C3')))
              AND (#{cursorAt} IS NULL
                OR (p.published_at, p.id) < (#{cursorAt}, #{cursorId}))
            ORDER BY p.published_at DESC, p.id
            LIMIT #{limit}
            """)
    java.util.List<WorkFeedRow> listVisibleWorks(@Param("bandVar") String bandVar,
                                                 @Param("cursorAt") java.time.LocalDateTime cursorAt,
                                                 @Param("cursorId") String cursorId,
                                                 @Param("limit") int limit);

    /** 已发布章节目录（读者 listChapters：SQL 等价过滤——指针即已发布事实）。 */
    @Select("""
            SELECT c.id, c.sequence_no, c.business_state
            FROM chapter c
            JOIN publication_pointer p ON p.target_type = 'CHAPTER' AND p.target_id = c.id
            WHERE c.work_id = #{workId} AND c.deleted_at IS NULL
            ORDER BY c.sequence_no
            """)
    java.util.List<ReaderChapterRow> listPublishedChapters(@Param("workId") String workId);
}
