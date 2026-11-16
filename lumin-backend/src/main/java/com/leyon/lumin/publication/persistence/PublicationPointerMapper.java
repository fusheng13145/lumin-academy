package com.leyon.lumin.publication.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * publication_pointer 表数据访问（INV-02：仅指向同目标 APPROVED 版本；
 * 原子交换由服务层事务完成——先改行再校验，唯一约束兜底并发）。
 */
@Mapper
public interface PublicationPointerMapper {

    @Insert("""
            INSERT INTO publication_pointer (id, target_type, target_id, version_id,
                published_at, publication_revision, created_at, updated_at,
                created_by, updated_by, lock_version)
            VALUES (#{row.id}, #{row.targetType}, #{row.targetId}, #{row.versionId},
                #{row.publishedAt}, #{row.publicationRevision}, #{row.createdAt},
                #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@Param("row") PublicationPointerRow row);

    @Select("""
            SELECT * FROM publication_pointer
            WHERE target_type = #{targetType} AND target_id = #{targetId}
            """)
    PublicationPointerRow findByTarget(@Param("targetType") String targetType,
                                       @Param("targetId") String targetId);

    /** 原子交换：乐观锁 + 指针列更新（publication_revision 单调由服务层校验）。 */
    @Update("""
            UPDATE publication_pointer SET version_id = #{versionId},
                published_at = #{publishedAt}, publication_revision = #{publicationRevision},
                updated_at = #{now}, updated_by = #{operator}, lock_version = lock_version + 1
            WHERE id = #{id} AND lock_version = #{expectedLockVersion}
            """)
    int swapVersion(@Param("id") String id, @Param("versionId") String versionId,
                    @Param("publishedAt") java.time.LocalDateTime publishedAt,
                    @Param("publicationRevision") long publicationRevision,
                    @Param("operator") String operator,
                    @Param("now") java.time.LocalDateTime now,
                    @Param("expectedLockVersion") long expectedLockVersion);
}
