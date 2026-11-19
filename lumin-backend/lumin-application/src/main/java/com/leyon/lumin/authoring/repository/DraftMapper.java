package com.leyon.lumin.authoring.repository;

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

    /** 事务内锁行读取（03 §4.1 第 5 条：提交锁定 draft 行，校验 lockVersion 和 EDITABLE）。 */
    @Select("SELECT * FROM draft WHERE id = #{id} FOR UPDATE")
    DraftRow findByIdForUpdate(@Param("id") String id);

    /** 提交迁移：state='SUBMITTED' + 乐观锁校验，行数 0 由服务层区分 B2001/B2002。 */
    @Update("""
            UPDATE draft SET state = 'SUBMITTED', updated_at = #{now}, updated_by = #{operator},
                lock_version = lock_version + 1
            WHERE id = #{id} AND state = 'EDITABLE' AND lock_version = #{expectedLockVersion}
                AND deleted_at IS NULL
            """)
    int submit(@Param("id") String id, @Param("expectedLockVersion") long expectedLockVersion,
               @Param("operator") String operator,
               @Param("now") java.time.LocalDateTime now);

    /** EDITABLE → 目标态（03 §2.2：仅可从 EDITABLE 迁出，行数 0 即被服务层判为非法迁移）。 */
    @Update("""
            UPDATE draft SET state = #{state}, updated_at = #{now}, updated_by = #{operator},
                lock_version = lock_version + 1
            WHERE id = #{id} AND state = 'EDITABLE' AND deleted_at IS NULL
            """)
    int transitionEditableTo(@Param("id") String id, @Param("state") String state,
                             @Param("operator") String operator,
                             @Param("now") java.time.LocalDateTime now);

    /** 作者草稿 keyset 分页（05 §3.2 listMyDrafts：先按作品归属过滤）。 */
    @Select("""
            <script>
            SELECT d.* FROM draft d
            JOIN work w ON w.id = d.work_id AND w.deleted_at IS NULL
            WHERE w.author_id = #{authorId} AND d.deleted_at IS NULL
            <if test="cursorAt != null">
              AND (d.updated_at, d.id) &lt; (#{cursorAt,jdbcType=TIMESTAMP}, #{cursorId,jdbcType=VARCHAR})
            </if>
            ORDER BY d.updated_at DESC, d.id DESC
            LIMIT #{limit}
            </script>
            """)
    java.util.List<DraftRow> listByAuthor(@Param("authorId") String authorId,
                                           @Param("cursorAt") java.time.LocalDateTime cursorAt,
                                           @Param("cursorId") String cursorId,
                                           @Param("limit") int limit);

    /** 丢弃草稿：仅 EDITABLE + 乐观锁，行数 0 由服务层区分 B2001/B2002。 */
    @Update("""
            UPDATE draft SET state = 'DISCARDED', updated_at = #{now}, updated_by = #{operator},
                lock_version = lock_version + 1
            WHERE id = #{id} AND state = 'EDITABLE' AND lock_version = #{expectedLockVersion}
                AND deleted_at IS NULL
            """)
    int discard(@Param("id") String id, @Param("expectedLockVersion") long expectedLockVersion,
                @Param("operator") String operator,
                @Param("now") java.time.LocalDateTime now);

    /**
     * 保存 EDITABLE 草稿内容（03 §2.2：If-Match 与 lockVersion 相同才允许，递增版本）。
     * 内容列仅此一处更新；owner 校验与状态判定由服务层完成。
     */
    @Update("""
            UPDATE draft SET title = #{title}, synopsis = #{synopsis},
                category_id = #{categoryId}, tags = #{tags}, body_text = #{bodyText},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND state = 'EDITABLE' AND lock_version = #{expectedLockVersion}
                AND deleted_at IS NULL
            """)
    int updateEditableContent(@Param("id") String id, @Param("title") String title,
                              @Param("synopsis") String synopsis,
                              @Param("categoryId") String categoryId,
                              @Param("tags") String tags, @Param("bodyText") String bodyText,
                              @Param("operator") String operator,
                              @Param("now") java.time.LocalDateTime now,
                              @Param("expectedLockVersion") long expectedLockVersion);
}
