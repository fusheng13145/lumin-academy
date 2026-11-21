package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * user_block 表数据访问（docs/13 UGC-043 一阶段；V19）。
 * U(blocker_id, blocked_id)；跨账号隔离按 blocker 查询；
 * InteractionGatePort 的裁决读路径在本表。
 */
@Mapper
public interface UserBlockMapper {

    @Insert("""
            INSERT INTO user_block (id, blocker_id, blocked_id, reason, created_at,
                created_by, updated_at, updated_by, lock_version)
            VALUES (#{row.id}, #{row.blockerId}, #{row.blockedId}, #{row.reason},
                #{row.createdAt}, #{row.createdBy}, #{row.updatedAt}, #{row.updatedBy},
                #{row.lockVersion})
            """)
    int insert(@Param("row") UserBlockRow row);

    @Select("SELECT * FROM user_block WHERE blocker_id = #{blockerId} AND blocked_id = #{blockedId}")
    UserBlockRow find(@Param("blockerId") String blockerId,
                      @Param("blockedId") String blockedId);

    @Select("""
            SELECT COUNT(*) > 0 FROM user_block
            WHERE blocker_id = #{blockerId} AND blocked_id = #{subjectId}
            """)
    boolean isBlocked(@Param("blockerId") String blockerId,
                      @Param("subjectId") String subjectId);

    /** 拉黑列表（拉黑时间倒序；含被拉黑人档案昵称供展示）。 */
    @Select("""
            SELECT b.id AS blockId, b.blocked_id AS blockedId,
                   COALESCE(u.nickname, '') AS blockedNickname, b.reason,
                   b.created_at AS createdAt
            FROM user_block b
            LEFT JOIN user_profile u ON u.id = b.blocked_id AND u.deleted_at IS NULL
            WHERE b.blocker_id = #{blockerId}
            ORDER BY b.created_at DESC, b.id
            LIMIT #{limit}
            """)
    List<BlockListItem> listByBlocker(@Param("blockerId") String blockerId,
                                      @Param("limit") int limit);

    @Delete("""
            DELETE FROM user_block WHERE blocker_id = #{blockerId} AND blocked_id = #{blockedId}
            """)
    int delete(@Param("blockerId") String blockerId,
               @Param("blockedId") String blockedId);

    /** 拉黑列表行（被拉黑人昵称仅向拉黑者本人呈现）。 */
    record BlockListItem(
            String blockId,
            String blockedId,
            String blockedNickname,
            String reason,
            LocalDateTime createdAt) {
    }
}
