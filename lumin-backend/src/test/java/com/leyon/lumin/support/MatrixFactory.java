package com.leyon.lumin.support;

import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.values.BusinessState;
import com.leyon.lumin.foundation.values.DraftState;
import com.leyon.lumin.foundation.values.Grade;
import com.leyon.lumin.foundation.values.PublicationEvent;
import com.leyon.lumin.foundation.values.VersionState;
import com.leyon.lumin.foundation.values.WorkflowState;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.provider.Arguments;

/**
 * 07 §2.2 T13/T15 组合生成器：以 @MethodSource 供 @ParameterizedTest 使用。
 *
 * <p>只依赖 foundation.values，不构造业务域对象，保证 T01 阶段即可编译。
 * VisibilityInput / TransitionContext 的具体装配由各测试类自行完成。
 */
public final class MatrixFactory {

    private MatrixFactory() {
    }

    /** 5 业务态 × 4 版本态 × 7 流程态 × 5 年龄层 × 4 内容等级 = 2800 组。 */
    public static Stream<Arguments> visibilityMatrix() {
        List<Arguments> matrix = new ArrayList<>(2800);
        for (BusinessState business : BusinessState.values()) {
            for (VersionState version : VersionState.values()) {
                for (WorkflowState workflow : WorkflowState.values()) {
                    for (AgeBand age : AgeBand.values()) {
                        for (Grade grade : Grade.values()) {
                            matrix.add(Arguments.of(business, version, workflow, age, grade));
                        }
                    }
                }
            }
        }
        return matrix.stream();
    }

    public static int visibilityMatrixSize() {
        return BusinessState.values().length * VersionState.values().length
                * WorkflowState.values().length * AgeBand.values().length
                * Grade.values().length;
    }

    /** 三张状态机的 (族, 状态, 事件) 全组合：15 态 × 19 事件 = 285 组。 */
    public static Stream<Arguments> stateEventMatrix() {
        List<Arguments> matrix = new ArrayList<>();
        for (BusinessState state : BusinessState.values()) {
            for (PublicationEvent event : PublicationEvent.values()) {
                matrix.add(Arguments.of("BUSINESS", state, event));
            }
        }
        for (DraftState state : DraftState.values()) {
            for (PublicationEvent event : PublicationEvent.values()) {
                matrix.add(Arguments.of("DRAFT", state, event));
            }
        }
        for (WorkflowState state : WorkflowState.values()) {
            for (PublicationEvent event : PublicationEvent.values()) {
                matrix.add(Arguments.of("WORKFLOW", state, event));
            }
        }
        return matrix.stream();
    }

    public static int stateEventMatrixSize() {
        return (BusinessState.values().length + DraftState.values().length
                + WorkflowState.values().length) * PublicationEvent.values().length;
    }

    /** 负向样本：未定义事件 + 终态迁移（含 TERMINATED 恢复、APPROVED/REJECTED/CANCELLED 任意迁移）。 */
    public static Stream<Arguments> illegalTransitions() {
        List<Arguments> cases = new ArrayList<>();
        cases.add(Arguments.of("BUSINESS", BusinessState.DRAFT, PublicationEvent.UNDEFINED));
        cases.add(Arguments.of("DRAFT", DraftState.EDITABLE, PublicationEvent.UNDEFINED));
        cases.add(Arguments.of("WORKFLOW", WorkflowState.SUBMITTED, PublicationEvent.UNDEFINED));
        cases.add(Arguments.of("BUSINESS", BusinessState.TERMINATED, PublicationEvent.RESUME_SERIAL));
        cases.add(Arguments.of("BUSINESS", BusinessState.TERMINATED, PublicationEvent.TEMPORARY_APPEAL_UPHELD));
        cases.add(Arguments.of("BUSINESS", BusinessState.TERMINATED, PublicationEvent.FIRST_VERSION_PUBLISHED));
        cases.add(Arguments.of("DRAFT", DraftState.SUBMITTED, PublicationEvent.SUBMIT));
        cases.add(Arguments.of("DRAFT", DraftState.DISCARDED, PublicationEvent.SAVE));
        cases.add(Arguments.of("WORKFLOW", WorkflowState.APPROVED, PublicationEvent.WITHDRAW));
        cases.add(Arguments.of("WORKFLOW", WorkflowState.REJECTED, PublicationEvent.HUMAN_FINAL_PASS));
        cases.add(Arguments.of("WORKFLOW", WorkflowState.CANCELLED, PublicationEvent.AUTO_TASK_CLAIMED));
        return cases.stream();
    }
}
