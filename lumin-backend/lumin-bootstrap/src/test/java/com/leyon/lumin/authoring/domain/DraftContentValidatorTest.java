package com.leyon.lumin.authoring.domain;

import com.leyon.lumin.catalog.domain.TargetRef;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.TargetType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 草稿载荷边界校验（03 §4.1 / 04 §4 draft / 05 §4.1；T07 前置）。 */
class DraftContentValidatorTest {

    private static final UUID WORK_ID =
            UUID.fromString("3f2a1b4c-5d6e-4f70-8a90-1b2c3d4e5f60");
    private static final UUID CHAPTER_ID =
            UUID.fromString("b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6a");
    private static final UUID CATEGORY_ID =
            UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d");

    private static final TargetRef WORK =
            new TargetRef(TargetType.WORK, WORK_ID, null);
    private static final TargetRef CHAPTER =
            new TargetRef(TargetType.CHAPTER, WORK_ID, CHAPTER_ID);

    @Test
    void validWorkAndChapterPayloads() {
        // 07 §1.1 固定正文
        assertEquals(ErrorCode.OK, DraftContentValidator.validate(
                WORK, "启明", "简介文本", CATEGORY_ID, List.of("奇幻", "冒险"), null));
        assertEquals(ErrorCode.OK, DraftContentValidator.validate(
                CHAPTER, "第一章", null, null, null, "第一段\n第二段"));
        // 05 §4.1：WORK 载荷 body 禁止、CHAPTER 载荷 synopsis/categoryId 禁止
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                WORK, "启明", "简介文本", CATEGORY_ID, List.of(), "正文"));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                CHAPTER, "第一章", "简介", null, null, "第一段"));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                CHAPTER, "第一章", null, CATEGORY_ID, null, "第一段"));
    }

    @Test
    void workRequiresSynopsisAndCategory() {
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                WORK, "启明", null, CATEGORY_ID, List.of(), null));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                WORK, "启明", "简介文本", null, List.of(), null));
    }

    @Test
    void titleBoundaries() {
        assertEquals(ErrorCode.OK, DraftContentValidator.validate(
                WORK, "a".repeat(100), "简介", CATEGORY_ID, List.of(), null));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                WORK, "a".repeat(101), "简介", CATEGORY_ID, List.of(), null));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                WORK, " ", "简介", CATEGORY_ID, List.of(), null));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                WORK, null, "简介", CATEGORY_ID, List.of(), null));
    }

    @Test
    void bodyCharacterAndByteBoundaries() {
        assertEquals(ErrorCode.OK, DraftContentValidator.validate(
                CHAPTER, "第一章", null, null, null, "a".repeat(100000)));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                CHAPTER, "第一章", null, null, null, "a".repeat(100001)));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                CHAPTER, "第一章", null, null, null, ""));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                CHAPTER, "第一章", null, null, null, null));
        // 字节上限（400KiB）在字符上限（100000 码点）之下不可触达：
        // UTF-8 单码点最长 4 字节 → 100000 码点 ≤ 400000 字节 < 409600；
        // 100000 个四字节字符恰好合法，验证字节计算不误伤
        String emoji = new String(Character.toChars(0x1F600));
        assertEquals(400000, emoji.repeat(100000)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(ErrorCode.OK, DraftContentValidator.validate(
                CHAPTER, "第一章", null, null, null, emoji.repeat(100000)));
    }

    @Test
    void synopsisAndTagsBoundaries() {
        assertEquals(ErrorCode.OK, DraftContentValidator.validate(
                WORK, "启明", "简".repeat(2000), CATEGORY_ID, List.of(), null));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                WORK, "启明", "简".repeat(2001), CATEGORY_ID, List.of(), null));
        assertEquals(ErrorCode.OK, DraftContentValidator.validate(
                WORK, "启明", "简介", CATEGORY_ID,
                List.of("一", "二", "三", "四", "五"), null));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                WORK, "启明", "简介", CATEGORY_ID,
                List.of("一", "二", "三", "四", "五", "六"), null));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                WORK, "启明", "简介", CATEGORY_ID, List.of(" ", "二"), null));
        // List.of 拒绝 null 元素，改用 Arrays.asList 构造含 null 的用例
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                WORK, "启明", "简介", CATEGORY_ID, java.util.Arrays.asList(null, "二"), null));
    }

    @Test
    void invalidTargetRefIsRejected() {
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                new TargetRef(TargetType.WORK, WORK_ID, CHAPTER_ID),
                "启明", "简介", CATEGORY_ID, List.of(), null));
        assertEquals(ErrorCode.P1001, DraftContentValidator.validate(
                null, "启明", "简介", CATEGORY_ID, List.of(), null));
    }
}
