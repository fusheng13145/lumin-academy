package com.leyon.lumin.foundation.values;

/** 年龄分层 C0–C4（01 §3.2、契约 AgeBand）。 */
public enum AgeBand {
    C0(0),
    C1(1),
    C2(2),
    C3(3),
    C4(1);

    /** 严格度偏序（U-04 工程解释）：数值越小越严格；C1 与 C4 同级。 */
    private final int restrictionRank;

    AgeBand(int restrictionRank) {
        this.restrictionRank = restrictionRank;
    }

    public int restrictionRank() {
        return restrictionRank;
    }

    /** 阅读能力口径：C4（未核实）按 C1 处理。 */
    public AgeBand readingBand() {
        return this == C4 ? C1 : this;
    }

    public boolean isVerifiedAdult() {
        return this == C3;
    }
}
