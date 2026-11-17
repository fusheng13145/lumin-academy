package com.leyon.lumin.publication.service;

import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.port.ActiveRestrictionsPort;
import com.leyon.lumin.foundation.port.PublicationGatePort;
import com.leyon.lumin.publication.repository.PublicationPointerMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 生效治理措施的业务态闸门适配器（03 §2.1；T23/T24/T26）：
 * 治理限制生效 → 作品/章节业务态 REMOVED（保存前态，SYSTEM）；
 * 改判解除 → 仅当所有生效限制均已解除（INV-03：生效限制优先于发布指针，
 * T24「其他限制存在继续不可见」）且发布指针仍在（重新校验）时恢复记录的前态。
 * P0 无边缘缓存：可见性逐请求裁决，下架即时生效（T23 的 60 秒口径在本骨架
 * 为 0 秒；边缘缓存随部署批次接入，登记）。
 */
@Service
public class PublicationGateAdapter implements PublicationGatePort {

    private final WorkService workService;
    private final PublicationPointerMapper pointerMapper;
    private final ActiveRestrictionsPort activeRestrictionsPort;
    private final com.leyon.lumin.foundation.audit.AuditRecorder auditRecorder;

    public PublicationGateAdapter(WorkService workService,
                                  PublicationPointerMapper pointerMapper,
                                  ActiveRestrictionsPort activeRestrictionsPort,
                                  com.leyon.lumin.foundation.audit.AuditRecorder auditRecorder) {
        this.workService = workService;
        this.pointerMapper = pointerMapper;
        this.activeRestrictionsPort = activeRestrictionsPort;
        this.auditRecorder = auditRecorder;
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.web.ApiEnvelope.currentTraceId();
    }

    @Override
    @Transactional
    public void takedown(String targetType, String workId, String chapterId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String before = workBusinessState(targetType, workId, chapterId);
        if ("CHAPTER".equals(targetType) && chapterId != null) {
            workService.markChapterTakedown(chapterId, "system", now);
        } else {
            workService.markTakedown(workId, "system", now);
        }
        auditRecorder.recordTransition(new com.leyon.lumin.foundation.audit.AuditRecorder
                        .AuditEntry("system", "SYSTEM", "takedown.effective", "work", workId,
                        "生效下架业务态迁移", traceId(), now.toInstant(ZoneOffset.UTC)),
                java.util.Map.of("businessState", before == null ? "" : before),
                java.util.Map.of("businessState", "REMOVED"));
    }

    @Override
    @Transactional
    public void restore(String targetType, String workId, String chapterId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 03 §2.1：所有生效限制均解除（含本条）才恢复；否则维持 REMOVED
        boolean workClean = activeRestrictionsPort.activeKinds("WORK", workId, null).isEmpty();
        boolean chapterClean = chapterId == null || activeRestrictionsPort
                .activeKinds("CHAPTER", workId, chapterId).isEmpty();
        if (!workClean || !chapterClean) {
            return;
        }
        // 重新校验指针（RESTORE 的 POINTER 前提）：指针已不在则不恢复公开态
        if (pointerMapper.findByTarget("WORK", workId) == null) {
            return;
        }
        if ("CHAPTER".equals(targetType) && chapterId != null) {
            workService.markChapterRestored(chapterId, "system", now);
        } else {
            workService.markRestored(workId, "system", now);
        }
        String after = workBusinessState(targetType, workId, chapterId);
        auditRecorder.recordTransition(new com.leyon.lumin.foundation.audit.AuditRecorder
                        .AuditEntry("system", "SYSTEM", "takedown.restored", "work", workId,
                        "改判恢复业务态迁移", traceId(), now.toInstant(ZoneOffset.UTC)),
                java.util.Map.of("businessState", "REMOVED"),
                java.util.Map.of("businessState", after == null ? "" : after));
    }

    /** 目标当前业务态（迁移审计 before/after 取值）。 */
    private String workBusinessState(String targetType, String workId, String chapterId) {
        if ("CHAPTER".equals(targetType) && chapterId != null) {
            return workService.chapterBusinessState(chapterId);
        }
        var summary = workService.getWorkSummary(workId);
        return summary == null ? null : summary.businessState();
    }
}
