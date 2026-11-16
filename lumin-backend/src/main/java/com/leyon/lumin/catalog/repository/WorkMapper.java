package com.leyon.lumin.catalog.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** work 表数据访问（04 §4；业务态迁移由 PublicationStateMachine 裁决后调用）。 */
@Mapper
public interface WorkMapper {

    @Insert("""
            INSERT INTO work (id, author_id, business_state, copyright_status,
                next_chapter_no, created_at, updated_at, created_by, updated_by)
            VALUES (#{row.id}, #{row.authorId}, #{row.businessState}, #{row.copyrightStatus},
                #{row.nextChapterNo}, #{row.createdAt}, #{row.updatedAt},
                #{row.createdBy}, #{row.updatedBy})
            """)
    int insert(@Param("row") WorkRow row);

    @Select("SELECT * FROM work WHERE id = #{id} AND deleted_at IS NULL")
    WorkRow findById(@Param("id") String id);
}
