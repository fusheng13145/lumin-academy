package com.leyon.lumin.engagement.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * notification_digest 表数据访问（docs/04 §6.1；V17；docs/13 UGC-037）：
 * U(recipient_id, work_id, local_date)——每作品每接收者每自然日最多 1 条；
 * 冲突以 DuplicateKeyException 交由消费者按「当日已聚合」处理（幂等去重）。
 */
@Mapper
public interface NotificationDigestMapper {

    @Insert("""
            INSERT INTO notification_digest (id, recipient_id, work_id, local_date,
                latest_chapter_id, sent_at, created_at, created_by, updated_at, updated_by,
                lock_version)
            VALUES (#{row.id}, #{row.recipientId}, #{row.workId}, #{row.localDate},
                #{row.latestChapterId}, #{row.sentAt}, #{row.createdAt}, #{row.createdBy},
                #{row.updatedAt}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") NotificationDigestRow row);

    /** 同接收者同作品最近一次聚合的自然日（无记录为 null；T34 lastDigestDate 注入）。 */
    @Select("""
            SELECT MAX(local_date) FROM notification_digest
            WHERE recipient_id = #{recipientId} AND work_id = #{workId}
            """)
    LocalDate findLastDigestDate(@Param("recipientId") String recipientId,
                                 @Param("workId") String workId);
}
