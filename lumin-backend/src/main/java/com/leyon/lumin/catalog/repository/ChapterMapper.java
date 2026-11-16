package com.leyon.lumin.catalog.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** chapter 表数据访问（04 §4；归属校验供 authoring 经 catalog 服务端口使用）。 */
@Mapper
public interface ChapterMapper {

    @Select("SELECT work_id FROM chapter WHERE id = #{id} AND deleted_at IS NULL")
    String findWorkIdById(@Param("id") String id);
}
