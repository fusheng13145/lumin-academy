package com.leyon.lumin.publication.domain;

import com.leyon.lumin.foundation.values.AccountStatus;
import com.leyon.lumin.foundation.values.BusinessState;
import com.leyon.lumin.foundation.values.VersionState;
import com.leyon.lumin.foundation.values.VisibilityReason;
import com.leyon.lumin.foundation.values.WorkflowState;
import com.leyon.lumin.identity.domain.AgePolicy;

import java.util.Objects;
import java.util.Set;

/**
 * 三态裁决纯函数（03 §3.1 / §8 单元③、docs/10 A.3.1）。
 *
 * <p>八步短路顺序不可交换；不访问数据库、不发日志、不改状态、不接受客户端
 * 指定版本；allowed=false 时 versionId 恒为 null。raiseIncident 只是布尔信号，
 * 具体事件由调用方按 02 §5.2 信封追加，P0-A 不写事件发布代码（U-05）。
 *
 * <p>publication → identity 是本批次唯一真实的跨业务域同步边，命中 02 §3.1 允许列表。
 */
public final class VisibilityPolicy {

    private VisibilityPolicy() {
    }

    public static VisibilityDecision decide(VisibilityInput input) {
        if (input == null) {
            return deny(VisibilityReason.INCONSISTENT, true);
        }

        // 步骤 1：权威读取失败 / 引用缺失 / 摘要不匹配 / 已发布却无指针
        if (!input.authoritativeReadOk()
                || isBlank(input.candidateVersionId())
                || isBlank(input.boundWorkflowId())
                || !input.materialSha256Matches()
                || (input.everPublished() && input.pointerVersionId() == null)) {
            return deny(VisibilityReason.INCONSISTENT, true);
        }

        // 步骤 2：生效限制 / REMOVED / TERMINATED / 账户被禁止访问
        Set<?> restrictions = input.activeRestrictions() == null
                ? Set.of() : input.activeRestrictions();
        if (!restrictions.isEmpty()
                || isBlocked(input.targetBusinessState())
                || isBlocked(input.parentWorkBusinessState())
                || input.accountStatus() != AccountStatus.ACTIVE) {
            return deny(VisibilityReason.BLOCKED, false);
        }

        // 步骤 3：无发布指针且尚无已发布事实（不告警）
        if (input.pointerVersionId() == null && !input.everPublished()) {
            return deny(VisibilityReason.NOT_PUBLISHED, false);
        }

        // 步骤 4：指针目标 / 版本所属目标 / 流程绑定 / 审批规则版本一致
        if (!Objects.equals(input.pointerTargetId(), input.targetId())
                || !Objects.equals(input.versionTargetId(), input.targetId())
                || !Objects.equals(input.workflowBoundVersionId(), input.pointerVersionId())
                || !Objects.equals(input.candidateVersionId(), input.pointerVersionId())
                || input.boundPolicyRevision() != input.activePolicyRevision()) {
            return deny(VisibilityReason.INCONSISTENT, true);
        }

        // 步骤 5：指针版本与绑定流程都必须 APPROVED
        if (input.candidateVersionState() != VersionState.APPROVED
                || input.boundWorkflowState() != WorkflowState.APPROVED) {
            return deny(VisibilityReason.INCONSISTENT, true);
        }

        // 步骤 6：目标或父作品 DRAFT 但存在指针
        if (input.targetBusinessState() == BusinessState.DRAFT
                || input.parentWorkBusinessState() == BusinessState.DRAFT) {
            return deny(VisibilityReason.INCONSISTENT, true);
        }

        // 步骤 7：请求者年龄不满足等级，或 A 级默认关闭 / 未确认（不告警）
        if (!AgePolicy.canRead(input.requesterEffectiveAgeBand(), input.candidateGrade(),
                input.adultContentEnabled(), input.adultEntryConfirmed())) {
            return deny(VisibilityReason.AGE_RESTRICTED, false);
        }

        // 步骤 8：全部通过，返回公开指针所指版本（不接受客户端指定版本）
        return new VisibilityDecision(true, VisibilityReason.ALLOW,
                input.pointerVersionId(), false);
    }

    private static boolean isBlocked(BusinessState state) {
        return state == BusinessState.REMOVED || state == BusinessState.TERMINATED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static VisibilityDecision deny(VisibilityReason reason, boolean raiseIncident) {
        return new VisibilityDecision(false, reason, null, raiseIncident);
    }
}
