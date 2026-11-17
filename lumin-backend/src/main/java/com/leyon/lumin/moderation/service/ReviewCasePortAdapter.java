package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.port.ReviewCasePort;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import com.leyon.lumin.moderation.repository.ReviewCaseRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 审核案件端口适配器（moderation 域所有权：review_case 唯一写入者，02 §3.1）。
 * 提交核验通过后创建 QUEUED 案件：source=SUBMISSION、stage=1、
 * 风险/优先级初值 LOW/P2（机审报告到达后按 N4 路由更新）；
 * SLA 初值按 P2 档（08 §5：2h 响应 / 24h 完成）。
 * **骨架登记**：queue_id 为固定合成默认队列；队列表与多队列路由后续批次落地。
 */
@Service
public class ReviewCasePortAdapter implements ReviewCasePort {

    /** 骨架默认审核队列（合成 UUID，非任何真实主体）。 */
    static final String DEFAULT_QUEUE_ID =
            UUID.fromString("7e7e7e7e-0000-4000-8000-000000000007").toString();
    /** P2 响应 SLA：2 小时（08 §5）。 */
    private static final long P2_RESPONSE_HOURS = 2;
    /** P2 完成 SLA：24 小时（08 §5）。 */
    private static final long P2_COMPLETION_HOURS = 24;

    private final ReviewCaseMapper reviewCaseMapper;

    public ReviewCasePortAdapter(ReviewCaseMapper reviewCaseMapper) {
        this.reviewCaseMapper = reviewCaseMapper;
    }

    @Override
    @Transactional
    public String createReportCase(ReportCaseRequest request) {
        if (request == null || request.workId() == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ReviewCaseRow row = new ReviewCaseRow(UUID.randomUUID().toString(), null, null,
                request.workId(), request.chapterId(), "REPORT", "LOW", "P2", "QUEUED",
                DEFAULT_QUEUE_ID, 1, null, null,
                now.plusHours(P2_RESPONSE_HOURS), now.plusHours(P2_COMPLETION_HOURS),
                request.policyVersion(), now, now, "system", "system", 1L, null);
        reviewCaseMapper.insert(row);
        return row.id();
    }

    @Override
    @Transactional
    public String createSubmissionCase(SubmissionCaseRequest request) {
        if (request == null || request.versionId() == null || request.workflowId() == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ReviewCaseRow row = new ReviewCaseRow(UUID.randomUUID().toString(),
                request.versionId(), request.workflowId(), request.workId(),
                request.chapterId(), "SUBMISSION", "LOW", "P2", "QUEUED",
                DEFAULT_QUEUE_ID, 1, null, null,
                now.plusHours(P2_RESPONSE_HOURS), now.plusHours(P2_COMPLETION_HOURS),
                request.policyVersion(), now, now, "system", "system", 1L, null);
        reviewCaseMapper.insert(row);
        return row.id();
    }
}
