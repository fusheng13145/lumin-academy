package com.leyon.lumin.engagement.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
}
