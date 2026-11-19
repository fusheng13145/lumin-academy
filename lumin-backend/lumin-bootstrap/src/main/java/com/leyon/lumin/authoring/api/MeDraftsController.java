package com.leyon.lumin.authoring.api;

import com.leyon.lumin.authoring.service.DraftCommandService;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 作者私有草稿列表（05 §3.2 listMyDrafts；仅本人，归属过滤后分页）。 */
@RestController
@RequestMapping("/api/v1/me/drafts")
public class MeDraftsController {

    private final DraftCommandService draftCommandService;

    public MeDraftsController(DraftCommandService draftCommandService) {
        this.draftCommandService = draftCommandService;
    }

    @GetMapping
    public ApiEnvelope listMyDrafts(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiEnvelope.ok(draftCommandService.listMyDrafts(subject, cursor, limit,
                LocalDateTime.now(ZoneOffset.UTC)));
    }
}
