package com.leyon.lumin.catalog.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** category 表读取（05 §3.2 listCategories：仅启用分类，keyset 分页）。 */
@Mapper
public interface CategoryMapper {

    @Select("""
            <script>
            SELECT * FROM category
            WHERE enabled = TRUE AND deleted_at IS NULL
            <if test="cursorSort != null">
              AND (sort_order, id) &gt; (#{cursorSort}, #{cursorId})
            </if>
            ORDER BY sort_order, id
            LIMIT #{limit}
            </script>
            """)
    java.util.List<CategoryRow> listEnabled(@Param("cursorSort") Integer cursorSort,
                                            @Param("cursorId") String cursorId,
                                            @Param("limit") int limit);
}
