package com.leyon.lumin.foundation.values;

/** 作品与章节业务态 5 态（03 §2.1）；章节禁止 COMPLETED。 */
public enum BusinessState {
    DRAFT,
    ACTIVE,
    COMPLETED,
    REMOVED,
    TERMINATED;

    /** TERMINATED 不可恢复传播，是唯一终态；REMOVED 可经独立申诉改判恢复。 */
    public boolean isTerminal() {
        return this == TERMINATED;
    }
}
