package com.leyon.lumin.foundation;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.support.FixedFixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T01 结构用例：错误码字典（05 §5 / libs/error-codes.json / 契约 / Java 枚举）四方一致，
 * 以及 07 §1.1 固定夹具的独立性。
 */
class ErrorCodesTest {

    private static final Pattern JSON_CODE = Pattern.compile(
            "\\{\\s*\"code\":\\s*\"([^\"]+)\"\\s*,\\s*\"http\":\\s*(\\d+)\\s*,"
                    + "\\s*\"message\":\\s*\"([^\"]+)\"\\s*\\}");
    private static final Pattern CONTRACT_ENUM = Pattern.compile(
            "ErrorResponse:[\\s\\S]*?code:\\s*\\{type: string, enum:\\s*\\[([^\\]]+)\\]\\}");
    private static final Pattern DOC_ROW = Pattern.compile(
            "^\\|\\s*([A-Z][0-9]{4})\\s*\\|\\s*(\\d+)\\s*\\|\\s*(.+?)\\s*\\|\\s*$",
            Pattern.MULTILINE);

    private static Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("contracts/p0.openapi.yaml"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("未找到包含 contracts/p0.openapi.yaml 的项目根目录");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(projectRoot().resolve(relative), StandardCharsets.UTF_8);
    }

    /** 返回 code -> (http, message)。 */
    private static Map<String, String[]> dictionary(String json) {
        Map<String, String[]> result = new LinkedHashMap<>();
        Matcher matcher = JSON_CODE.matcher(json);
        while (matcher.find()) {
            result.put(matcher.group(1), new String[]{matcher.group(2), matcher.group(3)});
        }
        return result;
    }

    @Test
    void javaEnumMatchesDictionaryExactly() throws IOException {
        Map<String, String[]> expected = dictionary(read("libs/error-codes.json"));
        assertEquals(14, ErrorCode.values().length, "05 §5 全集为 OK + 13 错误码");
        assertEquals(14, expected.size(), "字典应为 14 条");
        for (ErrorCode code : ErrorCode.values()) {
            String[] entry = expected.get(code.name());
            assertNotNull(entry, "字典缺少 " + code.name());
            assertEquals(Integer.parseInt(entry[0]), code.httpStatus(), code.name() + " 的 HTTP 不一致");
            assertEquals(entry[1], code.message(), code.name() + " 的稳定文案不一致");
        }
    }

    @Test
    void contractErrorCodesMatchDictionary() throws IOException {
        Map<String, String[]> expected = dictionary(read("libs/error-codes.json"));
        Matcher matcher = CONTRACT_ENUM.matcher(read("contracts/p0.openapi.yaml"));
        assertTrue(matcher.find(), "契约应存在 ErrorResponse.code 枚举");
        List<String> contractCodes = new ArrayList<>();
        for (String item : matcher.group(1).split(",")) {
            contractCodes.add(item.trim());
        }
        List<String> dictionaryErrors = expected.keySet().stream()
                .filter(code -> !"OK".equals(code)).toList();
        assertEquals(13, contractCodes.size(), "契约错误码应为 13 条");
        assertEquals(dictionaryErrors, contractCodes, "契约 ErrorResponse.code 与字典逐条一致");
    }

    @Test
    void doc05TableMatchesDictionary() throws IOException {
        Map<String, String[]> expected = dictionary(read("libs/error-codes.json"));
        Matcher matcher = DOC_ROW.matcher(read("docs/05-接口设计文档.md"));
        int rows = 0;
        while (matcher.find()) {
            String code = matcher.group(1);
            String[] entry = expected.get(code);
            assertNotNull(entry, "字典缺少 05 §5 的 " + code);
            assertEquals(matcher.group(2), entry[0], code + " 的 HTTP 与 05 §5 不一致");
            assertEquals(matcher.group(3), entry[1], code + " 的稳定文案与 05 §5 不一致");
            rows++;
        }
        assertEquals(13, rows, "05 §5 错误码表格应为 13 行");
    }

    @Test
    void fixedFixturesAreIndependentAndSynthetic() {
        assertEquals(Instant.parse("2026-11-11T16:00:00Z"),
                FixedFixtures.BUSINESS_CLOCK.instant(), "固定业务时钟必须与 07 §1.1 一致");
        assertEquals("Asia/Shanghai", FixedFixtures.BUSINESS_CLOCK.getZone().getId());
        assertEquals(18, FixedFixtures.ALL_IDS.size(), "应有 18 个合成固定 UUID");
        Set<java.util.UUID> unique = new HashSet<>(FixedFixtures.ALL_IDS);
        assertEquals(18, unique.size(), "固定 UUID 必须彼此独立");
        for (java.util.UUID id : FixedFixtures.ALL_IDS) {
            assertEquals(4, id.version(), "合成 UUID 应为固定 version 4 形态");
            assertTrue(id.toString().startsWith("00000000-0000-4000-8000-"),
                    "合成 UUID 形态必须单一来源");
        }
        assertEquals("第一段\n第二段", FixedFixtures.SYNTHETIC_BODY);
    }
}
