package com.leyon.lumin.engagement.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** reading_progress 表数据访问（docs/04 §5.1；U(user_id,chapter_id) 乐观锁）。 */
@Mapper
public interface ReadingProgressMapper {

    @Insert("""
            INSERT INTO reading_progress (id, user_id, chapter_id, version_id,
                position_basis_points, read_seconds, client_updated_at,
                created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.userId}, #{row.chapterId}, #{row.versionId},
                #{row.positionBasisPoints}, #{row.readSeconds}, #{row.clientUpdatedAt},
                #{row.createdAt}, #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy},
                #{row.lockVersion})
            """)
    int insert(@Param("row") ReadingProgressRow row);

    @Select("""
            SELECT * FROM reading_progress
            WHERE user_id = #{userId} AND chapter_id = #{chapterId}
            """)
    ReadingProgressRow find(@Param("userId") String userId,
                            @Param("chapterId") String chapterId);

    /** 乐观锁更新：版本重绑 + 位置/时长/客户端时间（仅本人行）。 */
    @Update("""
            UPDATE reading_progress SET version_id = #{versionId},
                position_basis_points = #{positionBasisPoints},
                read_seconds = #{readSeconds},
                client_updated_at = #{clientUpdatedAt},
                updated_at = #{now}, updated_by = #{operator},
                lock_version = lock_version + 1
            WHERE user_id = #{userId} AND chapter_id = #{chapterId}
                AND lock_version = #{expectedLockVersion}
            """)
    int update(@Param("userId") String userId, @Param("chapterId") String chapterId,
               @Param("versionId") String versionId,
               @Param("positionBasisPoints") int positionBasisPoints,
               @Param("readSeconds") int readSeconds,
               @Param("clientUpdatedAt") java.time.LocalDateTime clientUpdatedAt,
               @Param("operator") String operator,
               @Param("now") java.time.LocalDateTime now,
               @Param("expectedLockVersion") long expectedLockVersion);

    /** 阅读历史（UGC-035：本人进度行时间倒序，含作品/章节元数据；跨账号隔离）。 */
    @Select("""
            SELECT rp.id AS progressId, rp.updated_at AS updatedAt,
                   rp.position_basis_points AS positionBasisPoints,
                   rp.chapter_id AS chapterId, rp.version_id AS versionId,
                   c.work_id AS workId, c.sequence_no AS sequenceNo,
                   cv.title AS chapterTitle, cvw.title AS workTitle, cv.grade AS grade
            FROM reading_progress rp
            JOIN chapter c ON c.id = rp.chapter_id AND c.deleted_at IS NULL
            JOIN content_version cv ON cv.id = rp.version_id AND cv.state = 'APPROVED'
            JOIN work w2 ON w2.id = c.work_id AND w2.deleted_at IS NULL
            LEFT JOIN publication_pointer pw ON pw.target_type = 'WORK'
                AND pw.target_id = c.work_id
            LEFT JOIN content_version cvw ON cvw.id = pw.version_id
                AND cvw.state = 'APPROVED'
            WHERE rp.user_id = #{userId}
            ORDER BY rp.updated_at DESC, rp.id LIMIT #{limit}
            """)
    List<ReadingHistoryItem> listHistory(@Param("userId") String userId,
                                         @Param("limit") int limit);

    /** 阅读历史行（UGC-035；含可读口径的版本绑定元数据）。 */
    record ReadingHistoryItem(
            String progressId,
            LocalDateTime updatedAt,
            Integer positionBasisPoints,
            String chapterId,
            String versionId,
            String workId,
            Integer sequenceNo,
            String chapterTitle,
            String workTitle,
            String grade) {
    }
}