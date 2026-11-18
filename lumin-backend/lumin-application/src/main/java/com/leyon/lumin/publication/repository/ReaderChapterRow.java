package com.leyon.lumin.publication.repository;

/** 读者章节目录行（05 §3.4 listChapters：仅含已发布指针的章节）。 */
public record ReaderChapterRow(
        String id,
        int sequenceNo,
        String businessState) {
}
