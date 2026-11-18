package com.leyon.lumin.foundation.repository;

import com.leyon.lumin.foundation.event.OutboxEventRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * outbox_event 表数据访问（INV-06：与业务状态同事务写入由服务层保证；
 * U(aggregate_type,aggregate_id,aggregate_revision,event_type) 唯一约束兜底重复）。
 */
@Mapper
public interface OutboxEventMapper {

    @Insert("""
            INSERT INTO outbox_event (id, event_id, aggregate_type, aggregate_id,
                aggregate_revision, event_type, schema_version, payload, occurred_at,
                delivered_at, attempts, next_attempt_at, created_at, updated_at,
                created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.eventId}, #{row.aggregateType}, #{row.aggregateId},
                #{row.aggregateRevision}, #{row.eventType}, #{row.schemaVersion},
                #{row.payload}, #{row.occurredAt}, #{row.deliveredAt}, #{row.attempts},
                #{row.nextAttemptAt}, #{row.createdAt}, #{row.updatedAt},
                #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") OutboxEventRow row);

    @Select("SELECT * FROM outbox_event WHERE id = #{id}")
    OutboxEventRow findById(@Param("id") String id);

    /** 投递确认后记 delivered_at（03 §4.3：确认前不删事件）。 */
    @Update("""
            UPDATE outbox_event SET delivered_at = #{deliveredAt},
                updated_at = #{deliveredAt}, updated_by = #{operator},
                lock_version = lock_version + 1
            WHERE id = #{id} AND delivered_at IS NULL
            """)
    int markDelivered(@Param("id") String id,
                      @Param("deliveredAt") java.time.LocalDateTime deliveredAt,
                      @Param("operator") String operator);

    /** 待投递事件（未投递且到达下次尝试时间），按创建顺序批量扫描（03 §4.3）。 */
    @Select("""
            SELECT * FROM outbox_event
            WHERE delivered_at IS NULL
              AND (next_attempt_at IS NULL OR next_attempt_at <= #{now})
            ORDER BY created_at, id
            LIMIT #{limit}
            """)
    java.util.List<OutboxEventRow> selectPending(@Param("now") java.time.LocalDateTime now,
                                                 @Param("limit") int limit);

    /** 失败退避：attempts+1 并记下次尝试时间（1/5/30/60s，03 §4.3）；事件不删除。 */
    @Update("""
            UPDATE outbox_event SET attempts = #{attempts},
                next_attempt_at = #{nextAttemptAt}, updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND delivered_at IS NULL
            """)
    int recordFailure(@Param("id") String id, @Param("attempts") int attempts,
                      @Param("nextAttemptAt") java.time.LocalDateTime nextAttemptAt,
                      @Param("now") java.time.LocalDateTime now,
                      @Param("operator") String operator);
}
