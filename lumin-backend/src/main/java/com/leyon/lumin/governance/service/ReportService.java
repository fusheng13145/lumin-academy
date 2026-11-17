package com.leyon.lumin.governance.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.port.ReviewCasePort;
import com.leyon.lumin.governance.repository.ReportMapper;
import com.leyon.lumin.governance.repository.ReportRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 举报应用服务（03 §6 / 05 §3.3 createReport；R08）：
 * 举报创建并关联目标案件（moderation 建案 source=REPORT 经 ReviewCasePort）；
 * 同主体同目标 24h 内相同 reason_code 去重（04 §5）；举报人不泄露（05 §3.3）。
 * **骨架登记**：案件建案初值 risk/priority 同机审建案口径；高危信号
 * （reason_code 含 MINOR/ILLEGAL）由后续安全受理批次升级。
 */
@Service
public class ReportService {

    private static final long DEDUP_WINDOW_HOURS = 24;

    private final ReportMapper reportMapper;
    private final com.leyon.lumin.foundation.port.ReviewCasePort reviewCasePort;
    private final AuditRecorder auditRecorder;

    public ReportService(ReportMapper reportMapper,
                         com.leyon.lumin.foundation.port.ReviewCasePort reviewCasePort,
                         AuditRecorder auditRecorder) {
        this.reportMapper = reportMapper;
        this.reviewCasePort = reviewCasePort;
        this.auditRecorder = auditRecorder;
    }

    /** 创建举报并建案（moderation 建案 source=REPORT 经端口）。返回案件 ID。 */
    @Transactional
    public String createReport(String reporter, String workId, String chapterId,
                               String reasonCode, String description) {
        if (reasonCode == null || reasonCode.isBlank() || description == null
                || description.isBlank() || workId == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (reportMapper.countDuplicates(reporter, workId, reasonCode,
                now.minusHours(DEDUP_WINDOW_HOURS)) > 0) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        String caseId = reviewCasePort.createReportCase(
                new ReviewCasePort.ReportCaseRequest(workId, chapterId, 1L));
        ReportRow row = new ReportRow(UUID.randomUUID().toString(), reporter, workId,
                chapterId, reasonCode, description, caseId, now, now, reporter,
                reporter, 1L, null);
        reportMapper.insert(row);
        auditRecorder.record(new AuditRecorder.AuditEntry(reporter, "READER",
                "report.created", "report", row.id(), reasonCode,
                traceId(), now.toInstant(ZoneOffset.UTC)));
        return caseId;
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.web.ApiEnvelope.currentTraceId();
    }
}
