package com.leyon.lumin.foundation.port;

/**
 * 章节可读性端口（05 §3.4 saveProgress「本人且可读该章节」）：
 * engagement 域经本端口做读者可见裁决——裁决权在 publication
 * （VisibilityPolicy 八步），engagement 不自行判断（INV-03 归属边界）。
 * 实现方为 publication 域。
 */
public interface ChapterAccessPort {

    /**
     * 读者对章节的可见裁决：可读返回当前公开指针所指版本 ID（用于进度版本绑定），
     * 不可读/不存在返回 null（统一 404 语义，不泄露存在性）。
     */
    String readableVersion(String subject, String chapterId);
}
