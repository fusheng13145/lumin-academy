package com.leyon.lumin.authoring.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.TargetType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 材料快照与 materialSha256（03 §4.1 第 8 段；T18 相关断言：WORK 无正文仍匹配、
 * 元数据变化不得复用旧报告）。
 */
class MaterialSnapshotTest {

    private static final UUID WORK_ID =
            UUID.fromString("3f2a1b4c-5d6e-4f70-8a90-1b2c3d4e5f60");
    private static final UUID CHAPTER_ID =
            UUID.fromString("b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6a");
    private static final UUID CATEGORY_ID =
            UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d");
    private static final String BODY_SHA256 =
            "0123456789abcdef".repeat(4);

    @Test
    void workSnapshotCanonicalJsonIsStableAndSorted() throws Exception {
        MaterialSnapshot snapshot = new MaterialSnapshot(TargetType.WORK, WORK_ID, null,
                "启明", "简介文本", CATEGORY_ID, List.of("奇幻", "冒险"), null, null);
        assertEquals(ErrorCode.OK, snapshot.validate());

        // 手写期望串：键按 Unicode 码点排序、紧凑、null 保留、tags 保序、UUID 小写
        String expected = "{\"bodySha256\":null,"
                + "\"categoryId\":\"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d\","
                + "\"chapterId\":null,"
                + "\"coverAssetSha256\":null,"
                + "\"synopsis\":\"简介文本\","
                + "\"tags\":[\"奇幻\",\"冒险\"],"
                + "\"targetType\":\"WORK\","
                + "\"title\":\"启明\","
                + "\"workId\":\"3f2a1b4c-5d6e-4f70-8a90-1b2c3d4e5f60\"}";
        String actual = new String(snapshot.canonicalBytes(), StandardCharsets.UTF_8);
        assertEquals(expected, actual);

        // materialSha256 = 期望串字节的 SHA-256（测试内独立计算，不复用实现路径）
        String expectedSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        expected.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expectedSha, snapshot.materialSha256());
    }

    @Test
    void chapterSnapshotHasNoMetadataFields() throws Exception {
        MaterialSnapshot snapshot = new MaterialSnapshot(TargetType.CHAPTER, WORK_ID,
                CHAPTER_ID, "第一章", null, null, List.of(), null, BODY_SHA256);
        assertEquals(ErrorCode.OK, snapshot.validate());
        String actual = new String(snapshot.canonicalBytes(), StandardCharsets.UTF_8);
        assertTrue(actual.contains("\"bodySha256\":\"" + BODY_SHA256 + "\""));
        assertTrue(actual.contains("\"chapterId\":\"" + CHAPTER_ID + "\""));
        assertTrue(actual.contains("\"synopsis\":null"));
        assertTrue(actual.contains("\"categoryId\":null"));
        assertTrue(actual.contains("\"tags\":[]"));
        assertTrue(actual.contains("\"coverAssetSha256\":null"));
    }

    @Test
    void jsonStandardEscapingOnly() {
        MaterialSnapshot snapshot = new MaterialSnapshot(TargetType.WORK, WORK_ID, null,
                "a\"b\\c\nd\u0001e", "简介", CATEGORY_ID, List.of(), null, null);
        String actual = new String(snapshot.canonicalBytes(), StandardCharsets.UTF_8);
        // 03 §4.1：字符串按 JSON 标准转义；不额外去空白、不做 Unicode 合并、非 ASCII 不做转义
        // 期望串用拼接构造，避免源码字面 "反斜杠+u" 组合触发 javac Unicode 逃逸预处理
        String expectedTitleField = "\"title\":\"a\\\"b\\\\c\\nd\\" + "u0001e\"";
        assertTrue(actual.contains(expectedTitleField));
        assertTrue(actual.contains("\"synopsis\":\"简介\""));
    }

    @Test
    void sameValuesSameShaAndMetadataChangeChangesSha() throws Exception {
        MaterialSnapshot v1 = new MaterialSnapshot(TargetType.WORK, WORK_ID, null,
                "启明", "简介文本", CATEGORY_ID, List.of("奇幻", "冒险"), null, null);
        MaterialSnapshot v1Again = new MaterialSnapshot(TargetType.WORK, WORK_ID, null,
                "启明", "简介文本", CATEGORY_ID, List.of("奇幻", "冒险"), null, null);
        assertEquals(v1.materialSha256(), v1Again.materialSha256());
        assertArrayEquals(v1.canonicalBytes(), v1Again.canonicalBytes());

        // T18 补充断言：元数据变化不得复用旧报告
        MaterialSnapshot v2 = new MaterialSnapshot(TargetType.WORK, WORK_ID, null,
                "启明二", "简介文本", CATEGORY_ID, List.of("奇幻", "冒险"), null, null);
        assertNotEquals(v1.materialSha256(), v2.materialSha256());

        // tags 保序：数组顺序参与摘要，不按键排序
        MaterialSnapshot reordered = new MaterialSnapshot(TargetType.WORK, WORK_ID, null,
                "启明", "简介文本", CATEGORY_ID, List.of("冒险", "奇幻"), null, null);
        assertNotEquals(v1.materialSha256(), reordered.materialSha256());
    }

    @Test
    void structuralViolationsAreRejected() {
        // WORK 必有 synopsis/categoryId，bodySha256 键取 null
        assertEquals(ErrorCode.P1001, new MaterialSnapshot(TargetType.WORK, WORK_ID, null,
                "启明", null, CATEGORY_ID, List.of(), null, null).validate());
        assertEquals(ErrorCode.P1001, new MaterialSnapshot(TargetType.WORK, WORK_ID, null,
                "启明", "简介", null, List.of(), null, null).validate());
        assertEquals(ErrorCode.P1001, new MaterialSnapshot(TargetType.WORK, WORK_ID, null,
                "启明", "简介", CATEGORY_ID, List.of(), null, BODY_SHA256).validate());
        // CHAPTER 禁止 synopsis/categoryId/cover，正文摘要必填
        assertEquals(ErrorCode.P1001, new MaterialSnapshot(TargetType.CHAPTER, WORK_ID,
                CHAPTER_ID, "第一章", "简介", null, List.of(), null, BODY_SHA256).validate());
        assertEquals(ErrorCode.P1001, new MaterialSnapshot(TargetType.CHAPTER, WORK_ID,
                CHAPTER_ID, "第一章", null, CATEGORY_ID, List.of(), null, BODY_SHA256).validate());
        assertEquals(ErrorCode.P1001, new MaterialSnapshot(TargetType.CHAPTER, WORK_ID,
                CHAPTER_ID, "第一章", null, null, List.of(), null, null).validate());
        assertEquals(ErrorCode.P1001, new MaterialSnapshot(TargetType.CHAPTER, WORK_ID,
                CHAPTER_ID, "第一章", null, null, List.of("标签"), null, BODY_SHA256).validate());
        // 摘要格式
        assertEquals(ErrorCode.P1001, new MaterialSnapshot(TargetType.CHAPTER, WORK_ID,
                CHAPTER_ID, "第一章", null, null, List.of(), null, "ABCDEF".repeat(11))
                .validate());
        assertEquals(ErrorCode.P1001, new MaterialSnapshot(TargetType.WORK, WORK_ID, null,
                "启明", "简介", CATEGORY_ID, List.of(), "xyz", null).validate());
        // 标签数量
        assertEquals(ErrorCode.P1001, new MaterialSnapshot(TargetType.WORK, WORK_ID, null,
                "启明", "简介", CATEGORY_ID,
                List.of("一", "二", "三", "四", "五", "六"), null, null).validate());
        // 目标结构
        assertEquals(ErrorCode.P1001, new MaterialSnapshot(TargetType.WORK, WORK_ID,
                CHAPTER_ID, "启明", "简介", CATEGORY_ID, List.of(), null, null).validate());
    }

    @Test
    void coverAssetSha256AcceptedOnlyForWork() {
        String coverSha = "fedcba9876543210".repeat(4);
        assertEquals(ErrorCode.OK, new MaterialSnapshot(TargetType.WORK, WORK_ID, null,
                "启明", "简介", CATEGORY_ID, List.of(), coverSha, null).validate());
        // 封面属作品级：CHAPTER 快照 coverAssetSha256 不适用、取 null
        assertEquals(ErrorCode.P1001, new MaterialSnapshot(TargetType.CHAPTER, WORK_ID,
                CHAPTER_ID, "第一章", null, null, List.of(), coverSha, BODY_SHA256).validate());
    }
}
