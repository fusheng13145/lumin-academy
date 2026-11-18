package com.leyon.lumin.foundation.values;

/**
 * 草稿态 3 态（03 §2.2、契约 DraftView.state；U-11 裁决：无第 4 态）。
 * 退回后创建新的 EDITABLE 草稿是流程说明，不是新枚举值。
 */
public enum DraftState {
    EDITABLE,
    SUBMITTED,
    DISCARDED;

    /** SUBMITTED 冻结原草稿、DISCARDED 软删，两者均不可再迁移。 */
    public boolean isTerminal() {
        return this == SUBMITTED || this == DISCARDED;
    }
}
