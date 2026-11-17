package com.leyon.lumin.governance.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** report 表数据访问（04 §5；同主体同目标 24h 同 reason_code 去重由服务层校验）。 */
@Mapper
public interface ReportMapper {

    @Insert("""
            INSERT INTO report (id, reporter_id, work_id, chapter_id, reason_code,
                description, case_id, created_at, updated_at, created_by, updated_by)
            VALUES (#{row.id}, #{row.reporterId}, #{row.workId}, #{row.chapterId},
                #{row.reasonCode}, #{row.description}, #{row.caseId},
                #{row.createdAt}, #{row.updatedAt}, #{row.createdBy}, #{row.updatedBy})
            """)
    int insert(@Param("row") ReportRow row);

    @Select("""
            SELECT COUNT(*) FROM report
            WHERE reporter_id = #{reporterId} AND work_id = #{workId}
              AND reason_code = #{reasonCode}
              AND created_at > #{since} AND deleted_at IS NULL
            """)
    int countDuplicates(@Param("reporterId") String reporterId,
                        @Param("workId") String workId,
                        @Param("reasonCode") String reasonCode,
                        @Param("since") java.time.LocalDateTime since);
}
