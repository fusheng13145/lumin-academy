package com.leyon.lumin.moderation.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.Grade;
import com.leyon.lumin.foundation.values.ReviewDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** grade 配对规则穷举（05 §4.3 / 04 §5.2 末段）。 */
class ReviewDecisionRuleTest {

    private static final Grade[] GRADES = {null, Grade.G, Grade.PG_12, Grade.PG_16, Grade.A};

    @Test
    void gradeRequiringDecisionsMatrix() {
        Grade[] requiring = {Grade.G, Grade.PG_12, Grade.PG_16, Grade.A};
        for (ReviewDecision decision : ReviewDecision.values()) {
            for (Grade grade : GRADES) {
                ErrorCode expected;
                if (decision.requiresGrade()) {
                    expected = grade == null ? ErrorCode.P1001 : ErrorCode.OK;
                } else {
                    expected = grade == null ? ErrorCode.OK : ErrorCode.P1001;
                }
                assertEquals(expected, ReviewDecisionRule.check(decision, grade),
                        decision + " × " + grade);
            }
        }
        assertEquals(ErrorCode.P1001, ReviewDecisionRule.check(null, Grade.G));
    }

    @Test
    void requiresGradeIsExactlyPassLabelLimit() {
        for (ReviewDecision decision : ReviewDecision.values()) {
            boolean expected = decision == ReviewDecision.PASS
                    || decision == ReviewDecision.LABEL
                    || decision == ReviewDecision.LIMIT;
            assertEquals(expected, decision.requiresGrade(), decision.name());
        }
    }
}
