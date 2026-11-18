package com.leyon.lumin.engagement.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** bookmark 表数据访问（docs/04 §6.1；U(user_id,work_id)；跨账号隔离按主体查询）。 */
@Mapper
public interface BookmarkMapper {

    /** 收藏（幂等：重复收藏 0 行不报错）。 */
    @Insert("""
            INSERT IGNORE INTO bookmark (id, user_id, work_id, created_at, created_by,
                updated_at, updated_by, lock_version)
            VALUES (#{row.id}, #{row.userId}, #{row.workId}, #{row.createdAt},
                #{row.createdBy}, #{row.updatedAt}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insertIgnore(@Param("row") BookmarkRow row);

    @Select("SELECT COUNT(*) FROM bookmark WHERE user_id = #{userId} AND work_id = #{workId}")
    int exists(@Param("userId") String userId, @Param("workId") String workId);

    /** 书架列表（收藏时间倒序 + 作品元数据 + 可读状态口径字段）。 */
    @Select("""
            SELECT b.id AS bookmarkId, b.work_id AS workId, b.created_at AS createdAt,
                   w.business_state AS workState,
                   cv.id AS versionId, cv.title, cv.grade, cv.chapter_id AS chapterId,
                   COALESCE(u.nickname, '') AS author
            FROM bookmark b
            JOIN work w ON w.id = b.work_id AND w.deleted_at IS NULL
            LEFT JOIN publication_pointer p ON p.target_type = 'WORK' AND p.target_id = b.work_id
            LEFT JOIN content_version cv ON cv.id = p.version_id AND cv.state = 'APPROVED'
            LEFT JOIN user_profile u ON u.id = w.author_id AND u.deleted_at IS NULL
            WHERE b.user_id = #{userId}
            ORDER BY b.created_at DESC, b.id
            LIMIT #{limit}
            """)
    List<BookmarkListItem> listByUser(@Param("userId") String userId,
                                      @Param("limit") int limit);

    /** 取消收藏（按主体+作品；跨账号隔离）。 */
    @Delete("DELETE FROM bookmark WHERE user_id = #{userId} AND work_id = #{workId}")
    int deleteByUserAndWork(@Param("userId") String userId,
                            @Param("workId") String workId);
}
