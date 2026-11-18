package com.leyon.lumin.catalog.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** asset_upload 表数据访问（UGC-010；隔离上传会话）。 */
@Mapper
public interface AssetUploadMapper {

    @Insert("""
            INSERT INTO asset_upload (id, purpose, work_id, case_id, content_type,
                size_bytes, sha256_raw, object_key, state, asset_id, failure_reason,
                requested_by, expires_at, created_at, updated_at, created_by, updated_by,
                lock_version)
            VALUES (#{row.id}, #{row.purpose}, #{row.workId}, #{row.caseId},
                #{row.contentType}, #{row.sizeBytes}, #{row.sha256Raw}, #{row.objectKey},
                #{row.state}, #{row.assetId}, #{row.failureReason}, #{row.requestedBy},
                #{row.expiresAt}, #{row.createdAt}, #{row.updatedAt}, #{row.createdBy},
                #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") AssetUploadRow row);

    @Select("SELECT * FROM asset_upload WHERE id = #{id}")
    AssetUploadRow findById(@Param("id") String id);

    @Select("""
            SELECT * FROM asset_upload WHERE object_key = #{objectKey} FOR UPDATE
            """)
    AssetUploadRow findByObjectKeyForUpdate(@Param("objectKey") String objectKey);

    /** 状态迁移（乐观锁 + 期望态守卫）。 */
    @Update("""
            UPDATE asset_upload SET state = #{state}, asset_id = #{assetId},
                failure_reason = #{failureReason}, updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND state = #{expectedState}
                AND lock_version = #{expectedLockVersion}
            """)
    int transition(@Param("id") String id, @Param("state") String state,
                   @Param("assetId") String assetId,
                   @Param("failureReason") String failureReason,
                   @Param("expectedState") String expectedState,
                   @Param("expectedLockVersion") long expectedLockVersion,
                   @Param("operator") String operator, @Param("now") java.time.LocalDateTime now);
}
