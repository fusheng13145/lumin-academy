package com.leyon.lumin.foundation.web;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理契约（05 §5）：业务码映射 HTTP 状态与错误信封；
 * 未处理异常统一 D2001/503，且不得回显内部异常文案。
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void businessExceptionMapsToContractStatusAndEnvelope() throws Exception {
        mockMvc.perform(get("/probe/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("B2004"))
                .andExpect(jsonPath("$.message").value(ErrorCode.B2004.message()))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unexpectedExceptionReturnsStableD2001WithoutInternalDetail() throws Exception {
        mockMvc.perform(get("/probe/unexpected"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("D2001"))
                .andExpect(jsonPath("$.message").value(ErrorCode.D2001.message()))
                .andExpect(content().string(not(containsString("secret-detail"))));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/business")
        String business() {
            throw new LuminBusinessException(ErrorCode.B2004);
        }

        @GetMapping("/probe/unexpected")
        String unexpected() {
            throw new IllegalStateException("secret-detail");
        }
    }
}
