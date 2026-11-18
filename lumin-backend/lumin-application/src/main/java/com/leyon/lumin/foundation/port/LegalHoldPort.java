package com.leyon.lumin.foundation.port;

/**
 * 法律保全查询端口（03 §8 / 04 §5.2 legal_hold；T36）：
 * identity 注销推进经本端口查询 ACTIVE 保全——governance 域所有权，
 * identity 不直接触碰保全行。实现方为 governance 域。
 */
public interface LegalHoldPort {

    /** 该主体是否存在 ACTIVE 保全（USER/WORK/CASE）。 */
    boolean hasActive(String subjectType, String subjectId);

    /** ACTIVE 保全 ID（无则 null）。 */
    String activeHoldId(String subjectType, String subjectId);
}
