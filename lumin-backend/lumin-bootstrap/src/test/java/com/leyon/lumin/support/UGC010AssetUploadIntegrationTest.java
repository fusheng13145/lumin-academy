package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-010 素材上传一阶段集成测试（contracts/p0.openapi asset-uploads 切片）：
 * 隔离申请（COVER 归属校验/参数校验）→ 签名 PUT（大小/原始摘要核验）→
 * 完成校验（魔数/解码/像素/去 EXIF 重编码，正式 sha256 与原始分开）→
 * COMPLETE 签发 assetId → 受控内容读取（作者可读、非作者 404、nosniff）；
 * 篡改摘要 PUT 拒绝；格式非法 REJECTED。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC010AssetUploadIntegrationTest extends MySqlIntegrationBase {

    private static final String AUTHOR = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.AUTHOR;
    private static final String AUDITOR = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.AUDITOR;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private byte[] pngBytes;

    @BeforeEach
    void seedFixtures() throws Exception {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
        MySqlFixtures.insertConfigStubs(jdbc);
        // 生成确定性小 PNG（16×16 白底）
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        pngBytes = out.toByteArray();
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String field(org.springframework.test.web.servlet.ResultActions actions,
                                String name) throws java.io.UnsupportedEncodingException {
        return com.leyon.lumin.support.TestResponseSupport.field(actions, name);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    /** 申请 + 签名 PUT + 完成；返回 assetId（COMPLETE）。 */
    private String[] uploadCover(String workId, byte[] bytes) throws Exception {
        String grantBody = "{\"purpose\":\"COVER\",\"workId\":\"" + workId
                + "\",\"contentType\":\"image/png\",\"sizeBytes\":" + bytes.length
                + ",\"sha256\":\"" + sha256Hex(bytes) + "\"}";
        MvcResult grant = mockMvc.perform(post("/api/v1/asset-uploads")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content(grantBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("AWAITING_UPLOAD"))
                .andReturn();
        String uploadId = com.leyon.lumin.support.TestResponseSupport.field(grant, "id");
        String uploadUrl = "/api/v1" + com.leyon.lumin.support.TestResponseSupport.field(grant, "uploadUrl");
        // 签名 PUT（原始字节）
        mockMvc.perform(put(uploadUrl).content(bytes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CHECKING"));
        // 完成 → COMPLETE + assetId
        MvcResult done = mockMvc.perform(post("/api/v1/asset-uploads/" + uploadId + "/completions")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("COMPLETE"))
                .andReturn();
        String assetId = com.leyon.lumin.support.TestResponseSupport.field(done, "assetId");
        assertThat(assetId).isNotEmpty();
        return new String[]{uploadId, assetId};
    }

    @Test
    void coverUploadPipelineEndToEnd() throws Exception {
        String workId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works").header("X-Lumin-Demo-Subject", AUTHOR)
                                .header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String[] uploaded = uploadCover(workId, pngBytes);
        // 状态视图：COMPLETE + assetId 非空
        mockMvc.perform(get("/api/v1/asset-uploads/" + uploaded[0])
                        .header("X-Lumin-Demo-Subject", AUTHOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("COMPLETE"))
                .andExpect(jsonPath("$.data.assetId").value(uploaded[1]));
        // 受控内容：作者可读，字节即净化后 PNG
        MvcResult content = mockMvc.perform(get("/api/v1/assets/" + uploaded[1] + "/content")
                        .header("X-Lumin-Demo-Subject", AUTHOR))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        byte[] served = content.getResponse().getContentAsByteArray();
        assertThat(served[0] & 0xFF).isEqualTo(0x89);
        assertThat(sha256Hex(served)).isEqualTo(jdbc.queryForObject(
                "SELECT sha256 FROM content_asset WHERE id = ?", String.class, uploaded[1]));
        // 非作者（AUDITOR）→ 404（越权与不存在统一）
        mockMvc.perform(get("/api/v1/assets/" + uploaded[1] + "/content")
                        .header("X-Lumin-Demo-Subject", AUDITOR))
                .andExpect(status().isNotFound());
    }

    @Test
    void tamperedShaAndInvalidMagicAreRejected() throws Exception {
        String workId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works").header("X-Lumin-Demo-Subject", AUTHOR)
                                .header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        // 摘要与实际字节不符：PUT → 400（P1001）
        String grantId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/asset-uploads")
                                .header("X-Lumin-Demo-Subject", AUTHOR)
                                .header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"purpose\":\"COVER\",\"workId\":\"" + workId
                                        + "\",\"contentType\":\"image/png\",\"sizeBytes\":"
                                        + pngBytes.length + ",\"sha256\":\""
                                        + "0".repeat(64) + "\"}"))
                .andExpect(status().isOk()), "id");
        MvcResult grant2 = mockMvc.perform(post("/api/v1/asset-uploads")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"purpose\":\"COVER\",\"workId\":\"" + workId
                                + "\",\"contentType\":\"image/png\",\"sizeBytes\":"
                                + pngBytes.length + ",\"sha256\":\""
                                + sha256Hex(pngBytes) + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String uploadUrl = "/api/v1" + com.leyon.lumin.support.TestResponseSupport.field(grant2, "uploadUrl");
        // 篡改一字节 → 摘要不符 → 400
        byte[] tampered = pngBytes.clone();
        tampered[0] ^= 0x01;
        System.out.println("DBG-URL " + uploadUrl);
        var tamperedResult = mockMvc.perform(put(uploadUrl).content(tampered)).andReturn();
        System.out.println("DBG-TAMPER status=" + tamperedResult.getResponse().getStatus()
                + " body=" + tamperedResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(tamperedResult.getResponse().getStatus()).isEqualTo(400);
        // 非 PNG 魔数（声明 PNG 但内容为文本）→ CHECKING → 完成 REJECTED
        MvcResult grant3 = mockMvc.perform(post("/api/v1/asset-uploads")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"purpose\":\"COVER\",\"workId\":\"" + workId
                                + "\",\"contentType\":\"image/png\",\"sizeBytes\":"
                                + ("not-png-magic-body".length()) + ",\"sha256\":\""
                                + sha256Hex("not-png-magic-body".getBytes(StandardCharsets.UTF_8))
                                + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String url3 = "/api/v1" + com.leyon.lumin.support.TestResponseSupport.field(grant3, "uploadUrl");
        String upload3 = com.leyon.lumin.support.TestResponseSupport.field(grant3, "id");
        mockMvc.perform(put(url3).content("not-png-magic-body"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/asset-uploads/" + upload3 + "/completions")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("REJECTED"))
                .andExpect(jsonPath("$.data.assetId").doesNotExist());
        String state3 = jdbc.queryForObject(
                "SELECT state FROM asset_upload WHERE id = ?", String.class, upload3);
        assertThat(state3).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "SELECT failure_reason FROM asset_upload WHERE id = ?",
                String.class, upload3)).isEqualTo("INVALID_MAGIC");
        // 上传参数越界（>5MiB）→ 400
        mockMvc.perform(post("/api/v1/asset-uploads")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"purpose\":\"COVER\",\"workId\":\"" + workId
                                + "\",\"contentType\":\"image/png\",\"sizeBytes\":5242881,"
                                + "\"sha256\":\"" + sha256Hex(pngBytes) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
        // EVIDENCE 缺 caseId → 400
        mockMvc.perform(post("/api/v1/asset-uploads")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"purpose\":\"EVIDENCE\",\"contentType\":\"image/png\","
                                + "\"sizeBytes\":" + pngBytes.length + ",\"sha256\":\""
                                + sha256Hex(pngBytes) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
    }

}
