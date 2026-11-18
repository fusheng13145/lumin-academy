package com.leyon.lumin.identity.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.ServicePrincipals;
import com.leyon.lumin.foundation.values.RoleCode;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 角色权限纯函数（01 §3.3、03 INV-05 / §8 单元②、docs/10 A.3.1）。
 *
 * <p>表驱动 + 短路，<strong>未列组合一律默认拒绝</strong>；不访问数据库、
 * 不读当前系统时间、不产生 HTTP 响应/视图脱敏/水印/审计留痕（P0-B 范围）。
 * U-02：SYSTEM 不是 RoleCode 成员，只作固定服务主体特判。
 */
public final class RolePolicy {

    /** 01 §3.3 能力矩阵；未列出的动作对任何人类角色都默认拒绝。 */
    private static final Map<RoleCode, Set<Action>> ALLOWED_ACTIONS = Map.ofEntries(
            Map.entry(RoleCode.READER, Set.of(Action.READ_PUBLIC)),
            Map.entry(RoleCode.AUTHOR, Set.of(Action.READ_PUBLIC, Action.READ_PRIVATE_DRAFT,
                    Action.WRITE_DRAFT, Action.SUBMIT_DRAFT)),
            Map.entry(RoleCode.GUARDIAN, Set.of(Action.READ_PUBLIC)),
            Map.entry(RoleCode.REVIEWER_L1, Set.of(Action.READ_PUBLIC, Action.CLAIM_CASE,
                    Action.DECIDE_CASE)),
            Map.entry(RoleCode.REVIEWER_L2, Set.of(Action.READ_PUBLIC, Action.CLAIM_CASE,
                    Action.DECIDE_CASE, Action.REVIEW_APPEAL)),
            Map.entry(RoleCode.OPERATOR, Set.of(Action.READ_PUBLIC, Action.REVIEW_CONFIG)),
            Map.entry(RoleCode.ADMIN, Set.of(Action.READ_PUBLIC, Action.MANAGE_ROLES,
                    Action.APPROVE_ROLE_GRANT, Action.REVIEW_CONFIG)),
            Map.entry(RoleCode.AUDITOR, Set.of(Action.READ_AUDIT)),
            Map.entry(RoleCode.LEGAL, Set.of(Action.READ_PUBLIC, Action.REVIEW_APPEAL))
    );

    private static final Set<Action> PRIVATE_ACTIONS = Set.of(
            Action.READ_PRIVATE_DRAFT, Action.WRITE_DRAFT, Action.SUBMIT_DRAFT);

    private static final Set<Action> ADMIN_FORBIDDEN = Set.of(
            Action.DECIDE_CASE, Action.REVIEW_RESTRICTION,
            Action.REVIEW_APPEAL, Action.DELETE_AUDIT);

    private RolePolicy() {
    }

    /** 账号维度互斥：AUTHOR/OPERATOR × REVIEWER_L1/L2 不得并发授予。 */
    public static RoleSetValidation validateRoleSet(Set<RoleCode> roles) {
        Set<String> conflicts = new LinkedHashSet<>();
        if (roles == null || roles.isEmpty()) {
            return new RoleSetValidation(true, conflicts);
        }
        for (RoleCode account : Set.of(RoleCode.AUTHOR, RoleCode.OPERATOR)) {
            if (!roles.contains(account)) {
                continue;
            }
            for (RoleCode review : Set.of(RoleCode.REVIEWER_L1, RoleCode.REVIEWER_L2)) {
                if (roles.contains(review)) {
                    conflicts.add(account.name() + "_" + review.name());
                }
            }
        }
        return new RoleSetValidation(conflicts.isEmpty(), conflicts);
    }

    /** 审批时再次执行互斥校验（03 §2.4 role_grant）。 */
    public static boolean canAssign(Set<RoleCode> current, RoleCode candidate) {
        if (candidate == null) {
            return false;
        }
        Set<RoleCode> combined = new HashSet<>(current == null ? Set.of() : current);
        combined.add(candidate);
        return validateRoleSet(combined).compatible();
    }

    /** 八步短路授权（顺序不可交换；docs/10 A.3.1）。 */
    public static AuthorizationDecision authorize(AuthorizationContext ctx) {
        if (ctx == null) {
            return deny("DEFAULT_DENY");
        }
        Set<RoleCode> roles = ctx.activeRoles() == null ? Set.of() : ctx.activeRoles();
        Action action = ctx.action();
        UUID subject = ctx.subjectId();

        // 1. 固定服务主体：只执行确定的系统动作，不模拟人类审核员
        if (ServicePrincipals.isSystem(subject)) {
            return action == Action.SYSTEM_PUBLISH
                    ? allow("SYSTEM_PUBLISH_ALLOWED") : deny("SYSTEM_DENY");
        }
        // 2. 角色集互斥与授权租约
        if (!validateRoleSet(roles).compatible()) {
            return deny("ROLE_MUTEX_ACCOUNT");
        }
        if (ctx.roleExpiresAt() != null && ctx.now() != null
                && !ctx.roleExpiresAt().isAfter(ctx.now())) {
            return deny("ROLE_EXPIRED");
        }
        // 3. INV-05：同一身份不得申请自批
        if (ctx.applicantId() != null && ctx.applicantId().equals(ctx.approverId())) {
            return deny("SELF_APPROVAL");
        }
        // 4. INV-05：双审两席不得同主体
        if (ctx.reviewStage() == 2 && ctx.secondSeatActorId() != null
                && ctx.secondSeatActorId().equals(subject)) {
            return deny("DOUBLE_REVIEW_SAME_SUBJECT");
        }
        // 5. INV-05：原审裁决人不得参与申诉复核
        if (action == Action.REVIEW_APPEAL && ctx.originalDecisionActorIds() != null
                && ctx.originalDecisionActorIds().contains(subject)) {
            return deny("ORIGINAL_REVIEWER_RECURSION");
        }
        // 6. ADMIN 不得裁决内容、复核申诉或删除审计
        if (roles.contains(RoleCode.ADMIN) && ADMIN_FORBIDDEN.contains(action)) {
            return deny("ADMIN_NO_CONTENT_DECISION");
        }
        // 7. 表驱动能力矩阵 + 私有资源归属
        if (action == null) {
            return deny("DEFAULT_DENY");
        }
        boolean permitted = false;
        for (RoleCode role : roles) {
            if (ALLOWED_ACTIONS.getOrDefault(role, Set.of()).contains(action)) {
                permitted = true;
                break;
            }
        }
        if (!permitted) {
            return deny("DEFAULT_DENY");
        }
        if (PRIVATE_ACTIONS.contains(action)
                && (ctx.resourceOwnerId() == null || !ctx.resourceOwnerId().equals(subject))) {
            return deny("RESOURCE_NOT_OWNED");
        }
        // 8. 已列组合放行
        return allow("ALLOW_" + action.name());
    }

    /** 角色授予审批：不同主体 + ADMIN + 审批时再次互斥校验；第二名 ADMIN 属 P0-B 服务层。 */
    public static boolean canApproveRoleGrant(UUID applicantId, UUID approverId,
                                              Set<RoleCode> approverRoles,
                                              RoleCode candidateRole) {
        if (applicantId == null || approverId == null || applicantId.equals(approverId)) {
            return false;
        }
        if (approverRoles == null || !approverRoles.contains(RoleCode.ADMIN)) {
            return false;
        }
        Set<RoleCode> combined = new HashSet<>(approverRoles);
        if (candidateRole != null) {
            combined.add(candidateRole);
        }
        return validateRoleSet(combined).compatible();
    }

    /** 案件裁决：原审回避、双审两席、高风险二审与阶段 3 仲裁均须独立 L2。 */
    public static boolean canDecideCase(UUID actorId, Set<RoleCode> actorRoles,
                                        Set<UUID> originalDecisionActorIds, boolean highRisk,
                                        int reviewStage, UUID secondSeatActorId) {
        if (actorId == null || actorRoles == null || actorRoles.isEmpty()) {
            return false;
        }
        if (originalDecisionActorIds != null && originalDecisionActorIds.contains(actorId)) {
            return false;
        }
        if (!validateRoleSet(actorRoles).compatible()) {
            return false;
        }
        boolean level1 = actorRoles.contains(RoleCode.REVIEWER_L1);
        boolean level2 = actorRoles.contains(RoleCode.REVIEWER_L2);
        if (!level1 && !level2) {
            return false;
        }
        if (secondSeatActorId != null && secondSeatActorId.equals(actorId)) {
            return false;
        }
        if (reviewStage >= 3) {
            return level2;
        }
        if (highRisk && reviewStage >= 2) {
            return level2;
        }
        return true;
    }

    /** 申诉复核：原审全体回避，仅独立 L2 或 LEGAL。 */
    public static boolean canReviewAppeal(UUID reviewerId, Set<RoleCode> reviewerRoles,
                                          Set<UUID> originalDecisionActorIds) {
        if (reviewerId == null || reviewerRoles == null || reviewerRoles.isEmpty()) {
            return false;
        }
        if (originalDecisionActorIds != null && originalDecisionActorIds.contains(reviewerId)) {
            return false;
        }
        if (!validateRoleSet(reviewerRoles).compatible()) {
            return false;
        }
        return reviewerRoles.contains(RoleCode.REVIEWER_L2)
                || reviewerRoles.contains(RoleCode.LEGAL);
    }

    private static AuthorizationDecision allow(String ruleId) {
        return new AuthorizationDecision(true, ErrorCode.OK, ruleId);
    }

    private static AuthorizationDecision deny(String ruleId) {
        return new AuthorizationDecision(false, ErrorCode.A2001, ruleId);
    }
}
