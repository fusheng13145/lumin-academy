package com.leyon.lumin.ai.controller;

import com.leyon.lumin.ai.api.MemoryFactView;
import com.leyon.lumin.ai.workflow.AiMemoryService;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai/memory")
public class AiMemoryController {

    private final AiMemoryService memoryService;

    public AiMemoryController(AiMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/{workId}")
    public ApiEnvelope list(@PathVariable String workId) {
        List<MemoryFactView> facts = memoryService.list(workId);
        return ApiEnvelope.ok(facts);
    }

    @PostMapping("/{workId}/facts/{factId}/confirm")
    public ApiEnvelope confirm(
            @PathVariable String workId,
            @PathVariable String factId,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject) {
        return ApiEnvelope.ok(memoryService.confirm(workId, factId, subject));
    }
}
