package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** user_profile 表数据访问（04 §3；角色/年龄从权威记录读取，INV-07）。 */
@Mapper
public interface UserProfileMapper {

    @Select("SELECT * FROM user_profile WHERE id = #{id} AND deleted_at IS NULL")
    UserProfileRow findById(@Param("id") String id);
}
