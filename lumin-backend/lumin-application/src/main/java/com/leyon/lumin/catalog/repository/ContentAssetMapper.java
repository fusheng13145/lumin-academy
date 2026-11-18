package com.leyon.lumin.catalog.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** content_asset 表数据访问（净化后正式资源；不可变）。 */
@Mapper
public interface ContentAssetMapper {

    @Insert("""
            INSERT INTO content_asset (id, purpose, work_id, case_id, content_type,
                size_bytes, sha256, object_key, created_at, updated_at, created_by,
                updated_by, lock_version)
            VALUES (#{row.id}, #{row.purpose}, #{row.workId}, #{row.caseId},
                #{row.contentType}, #{row.sizeBytes}, #{row.sha256}, #{row.objectKey},
                #{row.createdAt}, #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy},
                #{row.lockVersion})
            """)
    int insert(@Param("row") ContentAssetRow row);

    @Select("SELECT * FROM content_asset WHERE id = #{id}")
    ContentAssetRow findById(@Param("id") String id);
}
