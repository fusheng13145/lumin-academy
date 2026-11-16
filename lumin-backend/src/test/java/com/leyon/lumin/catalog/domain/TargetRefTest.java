package com.leyon.lumin.catalog.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.TargetType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 目标引用校验（04 §2 / §4：WORK 无 chapter、CHAPTER 必属 work）。 */
class TargetRefTest {

    private static final UUID WORK_ID =
            UUID.fromString("3f2a1b4c-5d6e-4f70-8a90-1b2c3d4e5f60");
    private static final UUID CHAPTER_ID =
            UUID.fromString("b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6a");

    @Test
    void workTargetWithoutChapterIsValid() {
        assertEquals(ErrorCode.OK,
                new TargetRef(TargetType.WORK, WORK_ID, null).validate());
    }

    @Test
    void chapterTargetRequiresChapterId() {
        assertEquals(ErrorCode.OK,
                new TargetRef(TargetType.CHAPTER, WORK_ID, CHAPTER_ID).validate());
        assertEquals(ErrorCode.P1001,
                new TargetRef(TargetType.CHAPTER, WORK_ID, null).validate());
    }

    @Test
    void workTargetMustNotCarryChapterId() {
        assertEquals(ErrorCode.P1001,
                new TargetRef(TargetType.WORK, WORK_ID, CHAPTER_ID).validate());
    }

    @Test
    void nullTargetTypeOrWorkIdIsRejected() {
        assertEquals(ErrorCode.P1001, new TargetRef(null, WORK_ID, null).validate());
        assertEquals(ErrorCode.P1001, new TargetRef(TargetType.WORK, null, null).validate());
    }

    @Test
    void chapterOwnershipIsCheckedAgainstAuthoritativeWorkId() {
        TargetRef ref = new TargetRef(TargetType.CHAPTER, WORK_ID, CHAPTER_ID);
        assertEquals(ErrorCode.OK, ref.validateChapterBelongsTo(WORK_ID));
        assertEquals(ErrorCode.P1001,
                ref.validateChapterBelongsTo(UUID.fromString("c3d4e5f6-a7b8-4c9d-8e0f-1a2b3c4d5e6f")));
        assertEquals(ErrorCode.P1001, ref.validateChapterBelongsTo(null));

        // WORK 目标没有归属校验语义
        TargetRef workRef = new TargetRef(TargetType.WORK, WORK_ID, null);
        assertEquals(ErrorCode.P1001, workRef.validateChapterBelongsTo(WORK_ID));
    }
}
