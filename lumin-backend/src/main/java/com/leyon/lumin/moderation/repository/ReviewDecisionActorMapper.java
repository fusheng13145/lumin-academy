package com.leyon.lumin.moderation.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 原案件裁决主体查询（申诉复核回避集合，03 §6：复核人不得出现在原裁决记录）。 */
@Mapper
public interface ReviewDecisionActorMapper {

    @Select("SELECT actor_id FROM review_decision WHERE case_id = #{caseId}")
    List<String> findActorIdsByCaseId(@Param("caseId") String caseId);
}
