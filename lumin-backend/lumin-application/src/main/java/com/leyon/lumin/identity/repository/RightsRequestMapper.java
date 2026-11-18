package com.leyon.lumin.identity.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** rights_request 表数据访问（docs/04 §5.2 identity；UGC-006 数据主体权利）。 */
@Mapper
public interface RightsRequestMapper {

    @Insert("""
            INSERT INTO rights_request (id, user_id, kind, state, reason, due_at,
                hold_id, result_ref_cipher, created_at, updated_at, created_by,
                updated_by, lock_version)
            VALUES (#{row.id}, #{row.userId}, #{row.kind}, #{row.state}, #{row.reason},
                #{row.dueAt}, #{row.holdId}, #{row.resultRefCipher}, #{row.createdAt},
                #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") RightsRequestRow row);

    @Select("SELECT * FROM rights_request WHERE id = #{id}")
    RightsRequestRow findById(@Param("id") String id);

    /** 仅创建者本人可见（05 §3.4：不开放他人请求）。 */
    @Select("""
            SELECT * FROM rights_request WHERE user_id = #{userId}
            ORDER BY created_at DESC, id LIMIT #{limit}
            """)
    List<RightsRequestRow> listByUser(@Param("userId") String userId,
                                      @Param("limit") int limit);

    /** HELD 请求（保全解除后续跑）。 */
    @Select("SELECT * FROM rights_request WHERE state = 'HELD' AND kind = 'ERASE'")
    List<RightsRequestRow> listHeldErase();

    /** 状态推进（乐观锁）。 */
    @Update("""
            UPDATE rights_request SET state = #{state}, hold_id = #{holdId},
                result_ref_cipher = #{resultRefCipher}, updated_at = #{now},
                updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND state = #{expectedState}
                AND lock_version = #{expectedLockVersion}
            """)
    int transition(@Param("id") String id, @Param("state") String state,
                   @Param("holdId") String holdId, @Param("resultRefCipher") byte[] resultRefCipher,
                   @Param("expectedState") String expectedState,
                   @Param("expectedLockVersion") long expectedLockVersion,
                   @Param("operator") String operator, @Param("now") LocalDateTime now);
}
