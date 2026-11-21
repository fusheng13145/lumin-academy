package com.leyon.lumin.foundation.port;

/**
 * 互动安全闸端口（docs/13 UGC-043 一阶段）：「blocker 拉黑了 subjectId？」
 * 的唯一裁决读路径——评论（UGC-042）/关注（UGC-044）/通知过滤等一切
 * 用户间互动面在发起前经本端口裁决；identity 域为唯一实现方
 * （user_block 表唯一读取者，02 §3.2 跨模块 Repository 直访禁止）。
 */
public interface InteractionGatePort {

    /**
     * blocker 是否已拉黑 subjectId。拉黑是单向关系：
     * A 拉黑 B 只影响 A→B 方向的互动裁决（B 未拉黑 A 时 B→A 不受此闸限制）。
     */
    boolean isBlocked(String blockerId, String subjectId);
}
