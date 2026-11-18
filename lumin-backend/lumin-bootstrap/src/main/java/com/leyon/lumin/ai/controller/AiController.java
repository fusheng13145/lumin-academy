package com.leyon.lumin.ai.controller;

import com.leyon.lumin.ai.api.AiHealthView;
import com.leyon.lumin.ai.api.AiRunRequest;
import com.leyon.lumin.ai.api.AiRunView;
import com.leyon.lumin.ai.domain.AiCandidateState;
import com.leyon.lumin.ai.domain.AiMode;
import com.leyon.lumin.ai.workflow.AiRunCommand;
import com.leyon.lumin.ai.workflow.AiWorkflowService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 运行接口：POST 创建异步运行，GET 轮询阶段与候选。
 * 纯 AI 与 AI 辅助都走同一端点，由 mode 决定前端交互与后续写入策略。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiWorkflowService workflowService;

    public AiController(AiWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/runs")
    public ResponseEntity<ApiEnvelope> start(
            @RequestBody AiRunRequest request,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject) {
        AiMode mode = parseMode(request.mode());
        AiRunView view = workflowService.start(new AiRunCommand(
                request.workId(),
                request.chapterId(),
                mode,
                request.instruction(),
                subject
        ));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiEnvelope.accepted(view));
    }

    @GetMapping("/runs/{runId}")
    public ApiEnvelope get(@PathVariable String runId) {
        return ApiEnvelope.ok(workflowService.get(runId));
    }

    @PostMapping("/runs/{runId}/candidates/{candidateId}/accept")
    public ApiEnvelope acceptCandidate(@PathVariable String runId, @PathVariable String candidateId) {
        return ApiEnvelope.ok(workflowService.decideCandidate(runId, candidateId, AiCandidateState.ACCEPTED));
    }

    @PostMapping("/runs/{runId}/candidates/{candidateId}/reject")
    public ApiEnvelope rejectCandidate(@PathVariable String runId, @PathVariable String candidateId) {
        return ApiEnvelope.ok(workflowService.decideCandidate(runId, candidateId, AiCandidateState.REJECTED));
    }

    @GetMapping("/health")
    public ApiEnvelope health() {
        return ApiEnvelope.ok(workflowService.health());
    }

    private static AiMode parseMode(String raw) {
        try {
            return AiMode.valueOf(raw == null ? "" : raw.trim().toUpperCase());
        } catch (Exception exception) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
    }
}



