package com.leyon.lumin.moderation.api;

import com.leyon.lumin.foundation.web.ApiEnvelope;
import com.leyon.lumin.foundation.web.ReceiptView;
import com.leyon.lumin.moderation.domain.MachineReport;
import com.leyon.lumin.moderation.service.MachineReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机审报告接入层（05 §3.3 POST /internal/v1/machine-reports，Agent 专用）。
 * **骨架登记**：agentAuth（OIDC audience+scope 隔离）接入前，本端点由境内
 * 内网隔离边界保护（08 §2），不接受用户令牌身份；真实接入按 05 §2 验证。
 */
@RestController
@RequestMapping("/internal/v1/machine-reports")
public class MachineReportController {

    private final MachineReportService machineReportService;

    public MachineReportController(MachineReportService machineReportService) {
        this.machineReportService = machineReportService;
    }

    @PostMapping
    public ResponseEntity<ApiEnvelope> receive(@RequestBody MachineReport report) {
        MachineReportService.ReceiveResult result = machineReportService.receive(report);
        if (result.error() != null) {
            // 绑定核验/晚到报告等拒绝：审计已随事务留存，信封按错误码返回（05 §4.3）
            return ResponseEntity.status(result.error().httpStatus())
                    .body(ApiEnvelope.error(result.error()));
        }
        return ResponseEntity.ok().body(ApiEnvelope.ok(new ReceiptView(result.reportId(),
                "machine_report", "PROCESSING", 1L)));
    }
}
