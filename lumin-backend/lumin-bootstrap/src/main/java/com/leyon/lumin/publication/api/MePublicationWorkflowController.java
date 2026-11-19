package com.leyon.lumin.publication.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.publication.service.PublicationWorkflowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 作者私有发布流程列表（05 §3.2 listMyPublicationWorkflows；仅本人）。 */
@RestController
@RequestMapping("/api/v1/me/publication-workflows")
public class MePublicationWorkflowController {

    private final PublicationWorkflowService workflowService;

    public MePublicationWorkflowController(PublicationWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping
    public ApiEnvelope listMyPublicationWorkflows(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiEnvelope.ok(workflowService.listMyWorkflows(subject, cursor, limit,
                LocalDateTime.now(ZoneOffset.UTC)));
    }
}
