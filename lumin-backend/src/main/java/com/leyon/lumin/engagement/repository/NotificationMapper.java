package com.leyon.lumin.engagement.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** notification 表数据访问（04 §5.1；重复消费经 U(recipient,source_event,category) 幂等）。 */
@Mapper
public interface NotificationMapper {

    @Insert("""
            INSERT INTO notification (id, recipient_id, category, source_event_id, title,
                body, resource_type, resource_id, read_at, created_at, updated_at,
                created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.recipientId}, #{row.category}, #{row.sourceEventId},
                #{row.title}, #{row.body}, #{row.resourceType}, #{row.resourceId},
                #{row.readAt}, #{row.createdAt}, #{row.updatedAt}, #{row.createdBy},
                #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") NotificationRow row);

    @Select("SELECT id FROM notification WHERE recipient_id = #{recipientId} "
            + "AND source_event_id = #{sourceEventId} AND category = #{category}")
    String findIdByKey(@Param("recipientId") String recipientId,
                       @Param("sourceEventId") String sourceEventId,
                       @Param("category") String category);

    /** 标记已读（重复已读不重复产生副作用——T33）。 */
    @Update("""
            UPDATE notification SET read_at = #{readAt}, updated_at = #{readAt},
                updated_by = #{recipientId}, lock_version = lock_version + 1
            WHERE id = #{id} AND recipient_id = #{recipientId} AND read_at IS NULL
            """)
    int markRead(@Param("id") String id, @Param("recipientId") String recipientId,
                 @Param("readAt") java.time.LocalDateTime readAt);

    @Select("SELECT * FROM notification WHERE recipient_id = #{recipientId} "
            + "AND read_at IS NULL ORDER BY created_at DESC, id LIMIT 100")
    java.util.List<NotificationRow> listUnread(@Param("recipientId") String recipientId);
}
