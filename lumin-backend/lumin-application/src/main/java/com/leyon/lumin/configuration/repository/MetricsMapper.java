package com.leyon.lumin.configuration.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 可信看板只读面（docs/13 UGC-040 一阶段）：全部指标经权威表 SQL 直读
 * （COUNT DISTINCT 天然事件去重）；合成流量剔除口径见
 * {@link com.leyon.lumin.configuration.service.MetricsService}。
 * 本 Mapper 无写路径——看板不触碰业务行。
 */
@Mapper
public interface MetricsMapper {

    /** 有效阅读（冻结口径 v1）：窗口内更新的去重（subject, chapter）进度行，剔除合成流量。 */
    @Select("""
            SELECT COUNT(DISTINCT rp.user_id, rp.chapter_id)
            FROM reading_progress rp
            JOIN user_profile u ON u.id = rp.user_id AND u.deleted_at IS NULL
                AND u.account_status = 'ACTIVE'
            WHERE rp.updated_at >= #{windowStart} AND u.oidc_subject NOT LIKE 'sub-%'
            """)
    long countEffectiveReads(@Param("windowStart") LocalDateTime windowStart);

    /** 被剔除的合成流量读数（透明呈现剔除量，不隐藏口径）。 */
    @Select("""
            SELECT COUNT(DISTINCT rp.user_id, rp.chapter_id)
            FROM reading_progress rp
            JOIN user_profile u ON u.id = rp.user_id AND u.deleted_at IS NULL
            WHERE rp.updated_at >= #{windowStart} AND u.oidc_subject LIKE 'sub-%'
            """)
    long countSyntheticReads(@Param("windowStart") LocalDateTime windowStart);

    /** 分群（年龄带）有效阅读。 */
    @Select("""
            SELECT u.effective_age_band AS band,
                   COUNT(DISTINCT rp.user_id, rp.chapter_id) AS readCount
            FROM reading_progress rp
            JOIN user_profile u ON u.id = rp.user_id AND u.deleted_at IS NULL
                AND u.account_status = 'ACTIVE'
            WHERE rp.updated_at >= #{windowStart} AND u.oidc_subject NOT LIKE 'sub-%'
            GROUP BY u.effective_age_band
            ORDER BY u.effective_age_band
            """)
    List<BandReads> readsByBand(@Param("windowStart") LocalDateTime windowStart);

    /** 留存计算的原始触点（去重 subject；周聚合在服务层完成）。 */
    @Select("""
            SELECT DISTINCT rp.user_id AS userId, rp.updated_at AS updatedAt
            FROM reading_progress rp
            JOIN user_profile u ON u.id = rp.user_id AND u.deleted_at IS NULL
                AND u.account_status = 'ACTIVE'
            WHERE rp.updated_at >= #{windowStart} AND u.oidc_subject NOT LIKE 'sub-%'
            """)
    List<ProgressTouch> progressTouches(@Param("windowStart") LocalDateTime windowStart);

    /** 作者提交总数（发布流程行）。 */
    @Select("SELECT COUNT(*) FROM publication_workflow")
    long countSubmissions();

    /** 审核通过数。 */
    @Select("SELECT COUNT(*) FROM publication_workflow WHERE state = 'APPROVED'")
    long countApproved();

    /** 审核驳回数。 */
    @Select("SELECT COUNT(*) FROM publication_workflow WHERE state = 'REJECTED'")
    long countRejected();

    /** 生效下架在册数（业务态 REMOVED）。 */
    @Select("""
            SELECT COUNT(*) FROM work
            WHERE business_state = 'REMOVED' AND deleted_at IS NULL
            """)
    long countTakedownActive();

    /** 生效运营精选在册数。 */
    @Select("SELECT COUNT(*) FROM curation_item WHERE state = 'ACTIVE'")
    long countFeaturedActive();

    /** 真实读者档案数（剔除合成流量）。 */
    @Select("""
            SELECT COUNT(*) FROM user_profile
            WHERE deleted_at IS NULL AND oidc_subject NOT LIKE 'sub-%'
            """)
    long countRealReaders();

    /** 合成流量档案数（被剔除口径的透明呈现）。 */
    @Select("""
            SELECT COUNT(*) FROM user_profile
            WHERE deleted_at IS NULL AND oidc_subject LIKE 'sub-%'
            """)
    long countSyntheticProfiles();

    /** 分群读数行（readCount：reads 为 MySQL 保留字，列别名避开）。 */
    record BandReads(String band, long readCount) {
    }

    /** 留存触点行。 */
    record ProgressTouch(String userId, LocalDateTime updatedAt) {
    }
}
