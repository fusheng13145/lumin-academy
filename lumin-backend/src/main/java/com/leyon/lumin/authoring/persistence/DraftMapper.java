package com.leyon.lumin.authoring.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** draft 表数据访问（03 §2.2 草稿态迁移由服务层经状态机裁决后调用）。 */
@Mapper
public interface DraftMapper {

    @Insert("""
            INSERT INTO draft (id, work_id, chapter_id, target_type, base_version_id, title,
                synopsis, category_id, tags, cover_asset_id, body_text, state,
                created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.workId}, #{row.chapterId}, #{row.targetType},
                #{row.baseVersionId}, #{row.title}, #{row.synopsis}, #{row.categoryId},
                #{row.tags}, #{row.coverAssetId}, #{row.bodyText}, #{row.state},
                #{row.createdAt}, #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy},
                #{row.lockVersion})
            """)
    int insert(@Param("row") DraftRow row);

    @Select("SELECT * FROM draft WHERE id = #{id}")
    DraftRow findById(@Param("id") String id);

    /** EDITABLE → 目标态（03 §2.2：仅可从 EDITABLE 迁出，行数 0 即被服务层判为非法迁移）。 */
    @Update("""
            UPDATE draft SET state = #{state}, updated_at = #{now}, updated_by = #{operator},
                lock_version = lock_version + 1
            WHERE id = #{id} AND state = 'EDITABLE' AND deleted_at IS NULL
            """)
    int transitionEditableTo(@Param("id") String id, @Param("state") String state,
                             @Param("operator") String operator,
                             @Param("now") java.time.LocalDateTime now);
}
