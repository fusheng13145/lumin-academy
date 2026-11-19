package com.leyon.lumin.catalog.repository;

import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * curation_item 表数据访问（docs/13 UGC-039；V18）：运营精选提案/审批/撤位。
 * featured 曝光经本模块自有 SQL 完成——可见性谓词与
 * PublicationPointerMapper.listVisibleWorks 同源（等级带 + 业务态 + 指针，
 * 不绕过适龄与版权；02 §3.2 跨模块 Repository 直访禁止，表读取为本域
 * 自有 Mapper 的 SQL 责任）。
 */
@Mapper
public interface CurationMapper {

    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO curation_item (id, rule_version, work_id, exposure_reason, note,
                state, position_rank, proposed_by, reviewed_by, created_at, created_by,
                updated_at, updated_by, lock_version)
            VALUES (#{row.id}, #{row.ruleVersion}, #{row.workId}, #{row.exposureReason},
                #{row.note}, #{row.state}, #{row.positionRank}, #{row.proposedBy},
                #{row.reviewedBy}, #{row.createdAt}, #{row.createdBy}, #{row.updatedAt},
                #{row.updatedBy}, #{row.lockVersion})
            """)
    int insert(@org.apache.ibatis.annotations.Param("row") CurationItemRow row);

    @org.apache.ibatis.annotations.Select("""
            SELECT * FROM curation_item WHERE id = #{id}
            """)
    CurationItemRow findById(@org.apache.ibatis.annotations.Param("id") String id);

    /** 作品须已发布（有 WORK 指针且未删除）方可提案精选。 */
    @org.apache.ibatis.annotations.Select("""
            SELECT COUNT(*) FROM publication_pointer p
            JOIN work w ON w.id = p.target_id AND w.deleted_at IS NULL
            WHERE p.target_type = 'WORK' AND p.target_id = #{workId}
            """)
    int existsPublishedWork(@org.apache.ibatis.annotations.Param("workId") String workId);

    /** 审批/撤位（乐观锁；仅允许目标态迁移由服务层判定）。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE curation_item SET state = #{state}, reviewed_by = #{reviewedBy},
                updated_at = #{now}, updated_by = #{actor}, lock_version = lock_version + 1
            WHERE id = #{id} AND lock_version = #{expectedLock}
            """)
    int updateState(@org.apache.ibatis.annotations.Param("id") String id,
                    @org.apache.ibatis.annotations.Param("state") String state,
                    @org.apache.ibatis.annotations.Param("reviewedBy") String reviewedBy,
                    @org.apache.ibatis.annotations.Param("now") LocalDateTime now,
                    @org.apache.ibatis.annotations.Param("actor") String actor,
                    @org.apache.ibatis.annotations.Param("expectedLock") long expectedLock);

    /**
     * 读者精选位（ACTIVE + 可见性同源谓词；按显式干预排序 position_rank DESC——
     * 不伪装自然排序）。
     */
    @org.apache.ibatis.annotations.Select("""
            SELECT c.id AS itemId, c.exposure_reason AS exposureReason,
                   c.position_rank AS positionRank,
                   cv.id AS versionId, w.id AS workId, cv.title,
                   COALESCE(u.nickname, '') AS author, cv.grade, cv.chapter_id AS chapterId
            FROM curation_item c
            JOIN work w ON w.id = c.work_id
                AND w.business_state IN ('ACTIVE','COMPLETED') AND w.deleted_at IS NULL
            JOIN publication_pointer p ON p.target_type = 'WORK' AND p.target_id = c.work_id
            JOIN content_version cv ON cv.id = p.version_id AND cv.state = 'APPROVED'
            LEFT JOIN user_profile u ON u.id = w.author_id AND u.deleted_at IS NULL
            WHERE c.state = 'ACTIVE'
              AND (cv.grade IN ('G','PG_12')
                OR (cv.grade = 'PG_16' AND #{bandVar} IN ('C2','C3')))
            ORDER BY c.position_rank DESC, c.created_at DESC, c.id
            LIMIT #{limit}
            """)
    List<FeaturedListItem> listActive(@org.apache.ibatis.annotations.Param("bandVar") String bandVar,
                                      @org.apache.ibatis.annotations.Param("limit") int limit);

    /** 分类历史（不另建表：content_version 不可变版本链即历史）。 */
    @org.apache.ibatis.annotations.Select("""
            SELECT cv.id AS versionId, cv.revision_no AS revisionNo,
                   cv.category_id AS categoryId, cv.state AS versionState
            FROM content_version cv
            WHERE cv.work_id = #{workId}
            ORDER BY cv.revision_no, cv.id
            """)
    List<CategoryHistoryItem> listCategoryHistory(
            @org.apache.ibatis.annotations.Param("workId") String workId);

    /** 读者精选位行（含曝光原因——干预显式化）。 */
    record FeaturedListItem(
            String itemId,
            String exposureReason,
            Integer positionRank,
            String versionId,
            String workId,
            String title,
            String author,
            String grade,
            String chapterId) {
    }

    /** 分类历史行（版本不可变，category 随版本走）。 */
    record CategoryHistoryItem(
            String versionId,
            Integer revisionNo,
            String categoryId,
            String versionState) {
    }
}
