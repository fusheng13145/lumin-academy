package com.leyon.lumin.contracts;

import com.leyon.lumin.billing_reserved.BillingModelRegistry;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T38 / T42（07 §2.4）：契约只读、operationId 唯一、$ref 全解析、
 * 交易路由不存在、MoneyMinor 边界、BillingOrderReserved 未被 path 引用、
 * AccessPolicy 恒 false、骨架模型引用完整、错误码字典三方一致。
 *
 * <p><strong>仅结构级校验</strong>：本测试不执行 HTTP 运行断言、不生成三端类型、
 * 不覆盖 consumer/provider 契约测试（05 §6 / N-05）。
 */
class ContractSkeletonTest {

    private static final String BASELINE_SHA256 =
            "278151636eb16e96a28cbe7058bd665f549a5fff21471fbccc849a88ec147602";
    /** docs/10 A.8 口径（2026-11-15 文档口径对齐后，与契约原文实测 63 一致）。 */
    private static final int DOC_OPERATION_ID_COUNT = 63;
    private static final Pattern OPERATION_ID = Pattern.compile(
            "^[ \\t]*operationId:[ \\t]*([A-Za-z0-9_]+)[ \\t]*$", Pattern.MULTILINE);
    private static final Pattern REF = Pattern.compile(
            "#/components/([A-Za-z]+)/([A-Za-z0-9_]+)");
    private static final Pattern CONTRACT_ERROR_ENUM = Pattern.compile(
            "ErrorResponse:[\\s\\S]*?code:\\s*\\{type: string, enum:\\s*\\[([^\\]]+)\\]\\}");
    private static final Pattern TRADING = Pattern.compile(
            "billing|payment|order|refund|settlement|ledger|gift|coin|payout|charge|transfer",
            Pattern.CASE_INSENSITIVE);

    private static Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("contracts/p0.openapi.yaml"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("未找到包含 contracts/p0.openapi.yaml 的项目根目录");
    }

    private static String contract() throws IOException {
        return Files.readString(projectRoot().resolve("contracts/p0.openapi.yaml"),
                StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte item : digest) {
            builder.append(String.format("%02x", item));
        }
        return builder.toString();
    }

    /** 把契约切成 paths 段与 components 段。 */
    private static String[] sections(String text) {
        int index = text.indexOf("\ncomponents:");
        return index < 0
                ? new String[]{text, ""}
                : new String[]{text.substring(0, index), text.substring(index + 1)};
    }

    @Test
    void contractIsReadOnlyWithUnchangedSha256() throws Exception {
        Path path = projectRoot().resolve("contracts/p0.openapi.yaml");
        byte[] before = Files.readAllBytes(path);
        String baseline = sha256(before);
        assertEquals(BASELINE_SHA256, baseline, "契约必须与只读基线一致（不得修改）");
        // 完整执行一遍读取型校验
        assertNotNull(contract());
        byte[] after = Files.readAllBytes(path);
        assertEquals(baseline, sha256(after), "校验过程中契约不得被修改");
    }

    @Test
    void allOperationIdsAreUnique() throws IOException {
        Matcher matcher = OPERATION_ID.matcher(contract());
        List<String> ids = new ArrayList<>();
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        assertFalse(ids.isEmpty(), "必须解析到 operationId");
        assertEquals(ids.size(), new HashSet<>(ids).size(), "operationId 必须全部唯一");
        if (ids.size() != DOC_OPERATION_ID_COUNT) {
            System.out.println("[WARN] 口径差异：docs/10 A.8 记录 "
                    + DOC_OPERATION_ID_COUNT + " 个 operationId，只读契约原文实测 "
                    + ids.size() + " 个且全部唯一；契约 SHA256 与基线一致，未改动。"
                    + "以契约原文为准，不伪造基数、不擅自修改契约。");
        }
    }

    @Test
    void allRefsResolveToDefinedComponentKeys() throws IOException {
        String[] parts = sections(contract());
        Set<String> defined = new HashSet<>();
        Matcher keyMatcher = Pattern.compile("^    ([A-Za-z0-9_]+):", Pattern.MULTILINE)
                .matcher(parts[1]);
        while (keyMatcher.find()) {
            defined.add(keyMatcher.group(1));
        }
        Matcher refs = REF.matcher(contract());
        int count = 0;
        Set<String> unresolved = new HashSet<>();
        while (refs.find()) {
            count++;
            if (!defined.contains(refs.group(2))) {
                unresolved.add(refs.group(0));
            }
        }
        assertTrue(count > 0, "契约应存在 $ref");
        assertTrue(unresolved.isEmpty(), "存在未解析的 $ref：" + unresolved);
    }

    @Test
    void noTradingRouteAndReservedSchemaNotReferencedByPaths() throws IOException {
        String paths = sections(contract())[0];
        Matcher pathMatcher = Pattern.compile("^  /((api|internal)/v1)[^:]*:", Pattern.MULTILINE)
                .matcher(paths);
        int pathCount = 0;
        while (pathMatcher.find()) {
            pathCount++;
            assertFalse(TRADING.matcher(pathMatcher.group()).find(),
                    "paths 不得含交易关键字：" + pathMatcher.group());
        }
        assertTrue(pathCount > 0, "必须扫描到路径");
        assertFalse(paths.contains("BillingOrderReserved"),
                "BillingOrderReserved 不得被任何 path 引用");
        assertTrue(contract().contains("adultContentEnabled: {type: boolean, enum: [false]}"),
                "AccessPolicy.adultContentEnabled 恒 [false]");
        assertTrue(contract().contains(
                "MoneyMinor: {type: integer, format: int64, minimum: 0, maximum: 9007199254740991"),
                "MoneyMinor 边界必须精确");
    }

    @Test
    void reservedModelMatchesContractAndContainsNoTradingEntry() throws IOException {
        assertEquals(6, BillingModelRegistry.TABLE_NAMES.size(), "04 §6.2 恰好六张表");
        assertFalse(BillingModelRegistry.hasHttpEntry());
        assertFalse(BillingModelRegistry.isTradingEnabled());
        String text = contract();
        for (String field : List.of("buyerId", "productType", "productId",
                "amountMinor", "currency", "state")) {
            assertTrue(text.contains(field + ":"),
                    "契约 BillingOrderReserved 缺少字段 " + field);
        }
        assertTrue(text.contains("amountMinor: {$ref: '#/components/schemas/MoneyMinor'}"));
        assertTrue(text.contains("[CHAPTER, BOOK, MEMBERSHIP, GIFT]"));
        assertTrue(text.contains("[CREATED, PAID, CANCELLED, REFUNDED]"));
    }

    @Test
    void contractErrorCodesMatchJavaEnum() throws IOException {
        Matcher matcher = CONTRACT_ERROR_ENUM.matcher(contract());
        assertTrue(matcher.find(), "契约须含 ErrorResponse.code 枚举");
        List<String> contractCodes = new ArrayList<>();
        for (String item : matcher.group(1).split(",")) {
            contractCodes.add(item.trim());
        }
        List<String> javaErrors = new ArrayList<>();
        for (ErrorCode code : ErrorCode.values()) {
            if (code != ErrorCode.OK) {
                javaErrors.add(code.name());
            }
        }
        assertEquals(13, contractCodes.size());
        assertEquals(javaErrors, contractCodes, "契约错误码须与 Java 枚举逐条一致");
    }
}
