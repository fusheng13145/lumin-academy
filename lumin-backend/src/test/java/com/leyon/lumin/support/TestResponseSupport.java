package com.leyon.lumin.support;

import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

/** 集成测试响应字段提取（MySQL JSON 规范化容错：先压缩空白再定位）。 */
public final class TestResponseSupport {

    private TestResponseSupport() {
    }

    public static String field(org.springframework.test.web.servlet.ResultActions actions,
                               String name) throws java.io.UnsupportedEncodingException {
        return field(actions.andReturn(), name);
    }

    public static String field(MvcResult result, String name)
            throws java.io.UnsupportedEncodingException {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return field(body, name);
    }

    public static String field(String body, String name) {
        String compact = body.replace(" ", "");
        String token = "\"" + name + "\":\"";
        int start = compact.indexOf(token) + token.length();
        return compact.substring(start, compact.indexOf('"', start));
    }
}
