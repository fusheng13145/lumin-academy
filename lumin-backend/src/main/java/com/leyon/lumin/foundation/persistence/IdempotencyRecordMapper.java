package com.leyon.lumin.foundation.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * idempotency_record 表数据访问（03 §4.1 第 3 条 / T09：同键不同摘要 → B2003 由
 * 服务层依据 request_digest 差异判定；复合主键兜底并发插入）。
 */
@Mapper
public interface IdempotencyRecordMapper {

    @Insert("""
            INSERT INTO idempotency_record (subject_id, method, path, `key`, request_digest,
                response_code, response_body, expires_at)
            VALUES (#{row.subjectId}, #{row.method}, #{row.path}, #{row.key},
                #{row.requestDigest}, #{row.responseCode}, #{row.responseBody},
                #{row.expiresAt})
            """)
    int insert(@Param("row") IdempotencyRow row);

    @Select("""
            SELECT * FROM idempotency_record
            WHERE subject_id = #{subjectId} AND method = #{method}
              AND path = #{path} AND `key` = #{key}
            """)
    IdempotencyRow findByKey(@Param("subjectId") String subjectId,
                             @Param("method") String method, @Param("path") String path,
                             @Param("key") String key);
}
