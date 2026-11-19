package com.leyon.lumin.catalog.api;

import com.leyon.lumin.catalog.repository.CurationItemRow;
import com.leyon.lumin.catalog.repository.CurationMapper;
import com.leyon.lumin.catalog.service.CurationService;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营精选接入层（docs/13 UGC-039 一阶段；V18）：
 * 提案（OPERATOR）/独立审批（ADMIN）/撤位（ADMIN）/读者精选位/
 * 分类历史（OPERATOR/ADMIN）。读者精选位每项带 exposureReason 与
 * intervention=true——运营干预显式化，禁止假自然排序。
 */
@RestController
@RequestMapping("/api/v1")
public class CurationController {

    private final CurationService curationService;

    public CurationController(CurationService curationService) {
        this.curationService = curationService;
    }

    /** 提案运营精选（OPERATOR；作品须已发布；同规则版本同作品唯一 409）。 */
    @PostMapping("/curation-items")
    public ApiEnvelope propose(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @RequestBody ProposeRequest request) {
        CurationItemRow row = curationService.propose(subject, stubRole, request.workId(),
                request.exposureReason(), request.note(), request.ruleVersion(),
                LocalDateTime.now(ZoneOffset.UTC));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("ruleVersion", row.ruleVersion());
        view.put("workId", row.workId());
        view.put("exposureReason", row.exposureReason());
        view.put("state", row.state());
        view.put("positionRank", row.positionRank());
        return ApiEnvelope.ok(view);
    }

    /** 独立审批（ADMIN；PENDING→ACTIVE/REJECTED；自审经角色隔离结构性排除）。 */
    @PostMapping("/curation-items/{id}/reviews")
    public ApiEnvelope review(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @PathVariable String id,
            @RequestBody ReviewRequest request) {
        boolean approve = "APPROVE".equals(request.decision());
        if (!approve && !"REJECT".equals(request.decision())) {
            return ApiEnvelope.error(com.leyon.lumin.foundation.ErrorCodes.ErrorCode.P1001);
        }
        CurationItemRow row = curationService.review(subject, stubRole, id, approve,
                LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("id", row.id(), "state", row.state(),
                "workId", row.workId()));
    }

    /** 撤位（ADMIN；ACTIVE→RETIRED——精选位立即从读者曝光消失）。 */
    @DeleteMapping("/curation-items/{id}")
    public ApiEnvelope retire(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @PathVariable String id) {
        CurationItemRow row = curationService.retire(subject, stubRole, id,
                LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("id", row.id(), "state", row.state()));
    }

    /** 精选单列表（OPERATOR/ADMIN；state 可选过滤；提案时间倒序；上限 50）。 */
    @GetMapping("/curation-items")
    public ApiEnvelope list(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            String state) {
        List<CurationItemRow> rows = curationService.list(stubRole, state);
        return ApiEnvelope.ok(rows.stream()
                .map(row -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", row.id());
                    view.put("ruleVersion", row.ruleVersion());
                    view.put("workId", row.workId());
                    view.put("exposureReason", row.exposureReason());
                    view.put("note", row.note() == null ? "" : row.note());
                    view.put("state", row.state());
                    view.put("positionRank", row.positionRank());
                    view.put("proposedBy", row.proposedBy());
                    view.put("reviewedBy", row.reviewedBy() == null ? "" : row.reviewedBy());
                    view.put("createdAt", row.createdAt().toInstant(ZoneOffset.UTC).toString());
                    return view;
                })
                .toList());
    }

    /** 读者精选位（可见性同源过滤；每项 exposureReason + intervention=true）。 */
    @GetMapping("/works/featured")
    public ApiEnvelope featured(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject) {
        List<CurationMapper.FeaturedListItem> items = curationService.featured(subject);
        return ApiEnvelope.ok(items.stream()
                .map(item -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("workId", item.workId());
                    view.put("versionId", item.versionId());
                    view.put("title", item.title());
                    view.put("author", item.author());
                    view.put("grade", item.grade());
                    view.put("chapterId", item.chapterId());
                    view.put("exposureReason", item.exposureReason());
                    // 干预标识恒真：运营位显式标注，不伪装自然结果
                    view.put("intervention", true);
                    view.put("positionRank", item.positionRank());
                    return view;
                })
                .toList());
    }

    /** 分类历史（OPERATOR/ADMIN；content_version 不可变版本链）。 */
    @GetMapping("/works/{id}/category-history")
    public ApiEnvelope categoryHistory(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @PathVariable String id) {
        List<CurationMapper.CategoryHistoryItem> items =
                curationService.categoryHistory(stubRole, id);
        return ApiEnvelope.ok(items.stream()
                .map(item -> Map.of("versionId", item.versionId(),
                        "revisionNo", item.revisionNo() == null ? 0 : item.revisionNo(),
                        "categoryId", item.categoryId() == null ? "" : item.categoryId(),
                        "versionState", item.versionState()))
                .toList());
    }

    /** 精选提案请求体。 */
    public record ProposeRequest(String workId, String exposureReason, String note,
                                 Integer ruleVersion) {
    }

    /** 审批请求体（APPROVE/REJECT）。 */
    public record ReviewRequest(String decision) {
    }
}
