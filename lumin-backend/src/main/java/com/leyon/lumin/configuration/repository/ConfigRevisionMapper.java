package com.leyon.lumin.configuration.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** config_revision 表数据访问（docs/04 §5.1；同 key 唯一 ACTIVE 槽位由生成列兜底）。 */
@Mapper
public interface ConfigRevisionMapper {

    @Insert("""
            INSERT INTO config_revision (id, config_key, revision, base_revision,
                schema_version, payload, state, reason, requested_by, approved_by,
                effective_at, rollback_of, rollout_basis_points,
                created_at, updated_at, created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.configKey}, #{row.revision}, #{row.baseRevision},
                #{row.schemaVersion}, #{row.payload}, #{row.state}, #{row.reason},
                #{row.requestedBy}, #{row.approvedBy}, #{row.effectiveAt}, #{row.rollbackOf},
                #{row.rolloutBasisPoints}, #{row.createdAt}, #{row.updatedAt},
                #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") ConfigRevisionRow row);

    @Select("SELECT * FROM config_revision WHERE id = #{id}")
    ConfigRevisionRow findById(@Param("id") String id);

    @Select("SELECT * FROM config_revision WHERE id = #{id} FOR UPDATE")
    ConfigRevisionRow findByIdForUpdate(@Param("id") String id);

    /** 同 key 下一修订号（U(config_key,revision)；历史不改写，回滚也是新行）。 */
    @Select("SELECT COALESCE(MAX(revision), 0) + 1 FROM config_revision WHERE config_key = #{key}")
    Long nextRevision(@Param("key") String key);

    /** ACTIVE 全量基线（full_slot 生成列：state=ACTIVE 且 rollout=10000）。 */
    @Select("SELECT * FROM config_revision WHERE config_key = #{key} AND full_slot = #{key}")
    ConfigRevisionRow findActiveFull(@Param("key") String key);

    /** ACTIVE 灰度版本（gray_slot：state=ACTIVE 且 rollout 1–9999）。 */
    @Select("SELECT * FROM config_revision WHERE config_key = #{key} AND gray_slot = #{key}")
    ConfigRevisionRow findActiveGray(@Param("key") String key);

    /** 审查列表（先过滤后 limit；05 §3.4 listConfigRevisions）。 */
    @Select("""
            <script>
            SELECT * FROM config_revision
            <where>
                <if test="key != null">AND config_key = #{key}</if>
                <if test="state != null">AND state = #{state}</if>
            </where>
            ORDER BY config_key, revision DESC LIMIT #{limit}
            </script>
            """)
    List<ConfigRevisionRow> list(@Param("key") String key, @Param("state") String state,
                                 @Param("limit") int limit);

    /** 状态机字段更新（审批/激活/取代；approved_by 仅审批时回填，COALESCE 保留历史）。 */
    @Update("""
            UPDATE config_revision SET state = #{state},
                approved_by = COALESCE(#{approvedBy}, approved_by),
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND state = #{expectedState} AND lock_version = #{expectedLockVersion}
            """)
    int transition(@Param("id") String id, @Param("state") String state,
                   @Param("approvedBy") String approvedBy,
                   @Param("expectedState") String expectedState,
                   @Param("expectedLockVersion") long expectedLockVersion,
                   @Param("operator") String operator, @Param("now") LocalDateTime now);
}
