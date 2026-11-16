package com.leyon.lumin.authoring.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * content_version 表数据访问：内容字段不可变（04 §4——正文/元数据不可覆盖），
 * 仅提供状态/等级的状态机字段更新并要求携带审计主体。
 */
@Mapper
public interface ContentVersionMapper {

    @Insert("""
            INSERT INTO content_version (id, work_id, chapter_id, target_type, revision_no,
                source_draft_id, title, synopsis, category_id, tags, cover_asset_id,
                object_key, body_sha256, material_snapshot, material_sha256, byte_length,
                state, grade, policy_version, created_at, updated_at, created_by, updated_by,
                lock_version)
            VALUES (#{row.id}, #{row.workId}, #{row.chapterId}, #{row.targetType},
                #{row.revisionNo}, #{row.sourceDraftId}, #{row.title}, #{row.synopsis},
                #{row.categoryId}, #{row.tags}, #{row.coverAssetId}, #{row.objectKey},
                #{row.bodySha256}, #{row.materialSnapshot}, #{row.materialSha256},
                #{row.byteLength}, #{row.state}, #{row.grade}, #{row.policyVersion},
                #{row.createdAt}, #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy},
                #{row.lockVersion})
            """)
    int insert(@Param("row") ContentVersionRow row);

    @Select("SELECT * FROM content_version WHERE id = #{id}")
    ContentVersionRow findById(@Param("id") String id);

    /** 仅状态机合法字段更新：状态、等级、审计字段；其余列不出现在 UPDATE 中。 */
    @Update("""
            UPDATE content_version SET state = #{state}, grade = #{grade},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id}
            """)
    int transitionState(@Param("id") String id, @Param("state") String state,
                        @Param("grade") String grade, @Param("operator") String operator,
                        @Param("now") java.time.LocalDateTime now);
}
