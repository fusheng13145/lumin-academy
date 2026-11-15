package com.leyon.lumin.identity.domain;

import com.leyon.lumin.foundation.BusinessTime;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.ServicePrincipals;
import com.leyon.lumin.foundation.values.RoleCode;
import com.leyon.lumin.foundation.values.ScopeType;
import com.leyon.lumin.support.FixedFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T04–T06（07 §2.1）：9 个人类角色 × 动作矩阵、账号维度互斥、自批、
 * 双审两席、原审回避、ADMIN 禁止项、AUDITOR 只读、SYSTEM 特判、默认拒绝。
 *
 * <p>本测试只覆盖<strong>授权决策</strong>；HTTP 404/403、正文脱敏、水印与
 * 查看留痕属 P0-B 服务层，不在本阶段宣称覆盖（A.5 U-07 / N-05）。
 */
class RolePolicyTest {

    private static final Instant NOW = FixedFixtures.BUSINESS_CLOCK.instant();

    private static AuthorizationContext context(UUID subject, Set<RoleCode> roles, Action action,
                                                UUID resourceOwnerId, UUID applicantId,
                                                UUID approverId, Set<UUID> originals,
                                                int reviewStage, UUID secondSeat,
                                                Instant roleExpiresAt) {
        return new AuthorizationContext(subject, roles, action, resourceOwnerId,
                ScopeType.WORK, FixedFixtures.W, applicantId, approverId, originals,
                reviewStage, false, secondSeat, roleExpiresAt, NOW);
    }

    private static AuthorizationContext context(UUID subject, Set<RoleCode> roles, Action action) {
        return context(subject, roles, action, subject, null, null, Set.of(), 1, null, null);
    }

    /** 01 §3.3 能力/禁止项的预期矩阵（测试内独立 oracle）。 */
    private static boolean expectedAllowed(RoleCode role, Action action) {
        return switch (role) {
            case READER -> action == Action.READ_PUBLIC;
            case AUTHOR -> action == Action.READ_PUBLIC || action == Action.READ_PRIVATE_DRAFT
                    || action == Action.WRITE_DRAFT || action == Action.SUBMIT_DRAFT;
            case GUARDIAN -> action == Action.READ_PUBLIC;
            case REVIEWER_L1 -> action == Action.READ_PUBLIC || action == Action.CLAIM_CASE
                    || action == Action.DECIDE_CASE;
            case REVIEWER_L2 -> action == Action.READ_PUBLIC || action == Action.CLAIM_CASE
                    || action == Action.DECIDE_CASE || action == Action.REVIEW_APPEAL;
            case OPERATOR -> action == Action.READ_PUBLIC || action == Action.REVIEW_CONFIG;
            case ADMIN -> action == Action.READ_PUBLIC || action == Action.MANAGE_ROLES
                    || action == Action.APPROVE_ROLE_GRANT || action == Action.REVIEW_CONFIG;
            case AUDITOR -> action == Action.READ_AUDIT;
            case LEGAL -> action == Action.READ_PUBLIC || action == Action.REVIEW_APPEAL;
        };
    }

    private static Stream<Arguments> roleActionMatrix() {
        List<Arguments> cases = new ArrayList<>();
        for (RoleCode role : RoleCode.values()) {
            for (Action action : Action.values()) {
                cases.add(Arguments.of(role, action));
            }
        }
        return cases.stream();
    }

    /** 9 个人类角色 × 14 动作 = 126 组合，未列组合一律默认拒绝。 */
    @ParameterizedTest
    @MethodSource("roleActionMatrix")
    void roleActionMatrixMatchesSpec(RoleCode role, Action action) {
        AuthorizationDecision decision = RolePolicy.authorize(
                context(FixedFixtures.A, Set.of(role), action));
        if (expectedAllowed(role, action)) {
            assertTrue(decision.allowed(), role + " 应可执行 " + action);
            assertNotEquals("DEFAULT_DENY", decision.ruleId());
        } else {
            assertFalse(decision.allowed(), role + " 不得执行 " + action);
            assertEquals(ErrorCode.A2001, decision.denialCode());
        }
    }

    @Test
    void accountMutexBetweenAuthorOperatorAndReviewersIsRejected() {
        for (RoleCode review : List.of(RoleCode.REVIEWER_L1, RoleCode.REVIEWER_L2)) {
            for (RoleCode account : List.of(RoleCode.AUTHOR, RoleCode.OPERATOR)) {
                Set<RoleCode> roles = Set.of(account, review);
                RoleSetValidation validation = RolePolicy.validateRoleSet(roles);
                assertFalse(validation.compatible(), account + " 与 " + review + " 必须互斥");
                assertTrue(validation.conflictRuleIds().stream().anyMatch(
                        id -> id.contains(account.name()) && id.contains(review.name())));
                assertFalse(RolePolicy.canAssign(Set.of(account), review));
                AuthorizationDecision decision = RolePolicy.authorize(
                        context(FixedFixtures.A, roles, Action.READ_PUBLIC));
                assertFalse(decision.allowed());
                assertEquals("ROLE_MUTEX_ACCOUNT", decision.ruleId());
                assertEquals(ErrorCode.A2001, decision.denialCode());
            }
        }
    }

    @Test
    void compatibleRoleSetsPassAndCanAssign() {
        assertTrue(RolePolicy.validateRoleSet(Set.of(RoleCode.AUTHOR, RoleCode.GUARDIAN)).compatible());
        assertTrue(RolePolicy.validateRoleSet(Set.of()).compatible());
        assertTrue(RolePolicy.canAssign(Set.of(RoleCode.AUTHOR), RoleCode.GUARDIAN));
        assertFalse(RolePolicy.canAssign(Set.of(RoleCode.REVIEWER_L1), RoleCode.AUTHOR));
    }

    @Test
    void selfApprovalIsRejectedByInv05() {
        AuthorizationDecision decision = RolePolicy.authorize(context(
                FixedFixtures.X, Set.of(RoleCode.ADMIN), Action.APPROVE_ROLE_GRANT,
                FixedFixtures.X, FixedFixtures.X, FixedFixtures.X, Set.of(), 1, null, null));
        assertFalse(decision.allowed());
        assertEquals("SELF_APPROVAL", decision.ruleId());
    }

    @Test
    void doubleReviewSameSubjectIsRejected() {
        AuthorizationDecision decision = RolePolicy.authorize(context(
                FixedFixtures.L1A, Set.of(RoleCode.REVIEWER_L1), Action.DECIDE_CASE,
                FixedFixtures.L1A, null, null, Set.of(), 2, FixedFixtures.L1A, null));
        assertFalse(decision.allowed());
        assertEquals("DOUBLE_REVIEW_SAME_SUBJECT", decision.ruleId());
    }

    @Test
    void originalReviewerCannotReviewAppeal() {
        AuthorizationDecision decision = RolePolicy.authorize(context(
                FixedFixtures.L1A, Set.of(RoleCode.REVIEWER_L2), Action.REVIEW_APPEAL,
                FixedFixtures.L1A, null, null, Set.of(FixedFixtures.L1A), 2, null, null));
        assertFalse(decision.allowed());
        assertEquals("ORIGINAL_REVIEWER_RECURSION", decision.ruleId());
    }

    @Test
    void adminCannotDecideContentOrDeleteAudit() {
        for (Action action : List.of(Action.DECIDE_CASE, Action.REVIEW_RESTRICTION,
                Action.REVIEW_APPEAL, Action.DELETE_AUDIT)) {
            AuthorizationDecision decision = RolePolicy.authorize(
                    context(FixedFixtures.X, Set.of(RoleCode.ADMIN), action));
            assertFalse(decision.allowed(), "ADMIN 不得执行 " + action);
            assertEquals("ADMIN_NO_CONTENT_DECISION", decision.ruleId());
        }
    }

    @Test
    void auditorIsReadOnlyAndDeleteAuditAlwaysDenied() {
        assertTrue(RolePolicy.authorize(
                context(FixedFixtures.Z, Set.of(RoleCode.AUDITOR), Action.READ_AUDIT)).allowed());
        for (RoleCode role : RoleCode.values()) {
            AuthorizationDecision decision = RolePolicy.authorize(
                    context(FixedFixtures.A, Set.of(role), Action.DELETE_AUDIT));
            assertFalse(decision.allowed(), role + " 不得删除审计");
            assertEquals(ErrorCode.A2001, decision.denialCode());
        }
    }

    @Test
    void privateResourceRequiresOwnership() {
        AuthorizationDecision own = RolePolicy.authorize(context(
                FixedFixtures.A, Set.of(RoleCode.AUTHOR), Action.WRITE_DRAFT,
                FixedFixtures.A, null, null, Set.of(), 1, null, null));
        assertTrue(own.allowed());
        AuthorizationDecision other = RolePolicy.authorize(context(
                FixedFixtures.A, Set.of(RoleCode.AUTHOR), Action.WRITE_DRAFT,
                FixedFixtures.B, null, null, Set.of(), 1, null, null));
        assertFalse(other.allowed());
        assertEquals("RESOURCE_NOT_OWNED", other.ruleId());
    }

    @Test
    void systemPrincipalOnlyPerformsDeterministicSystemPublish() {
        AuthorizationDecision publish = RolePolicy.authorize(context(
                ServicePrincipals.SYSTEM_ID, Set.of(), Action.SYSTEM_PUBLISH));
        assertTrue(publish.allowed());
        assertEquals("SYSTEM_PUBLISH_ALLOWED", publish.ruleId());
        AuthorizationDecision decide = RolePolicy.authorize(context(
                ServicePrincipals.SYSTEM_ID, Set.of(), Action.DECIDE_CASE));
        assertFalse(decide.allowed());
        assertEquals("SYSTEM_DENY", decide.ruleId());
        assertEquals(9, RoleCode.values().length, "契约 Role 必须恰好 9 个人类角色（U-02）");
    }

    @Test
    void expiredRoleAssignmentIsRejected() {
        AuthorizationDecision decision = RolePolicy.authorize(context(
                FixedFixtures.A, Set.of(RoleCode.AUTHOR), Action.WRITE_DRAFT,
                FixedFixtures.A, null, null, Set.of(), 1, null,
                NOW.minusSeconds(1)));
        assertFalse(decision.allowed());
        assertEquals("ROLE_EXPIRED", decision.ruleId());
    }

    @Test
    void unknownCombinationDefaultsToDeny() {
        AuthorizationDecision decision = RolePolicy.authorize(context(
                FixedFixtures.A, Set.of(RoleCode.READER), Action.WRITE_DRAFT));
        assertFalse(decision.allowed());
        assertEquals("DEFAULT_DENY", decision.ruleId());
        assertEquals(ErrorCode.A2001, decision.denialCode());
    }

    @Test
    void canApproveRoleGrantRequiresDifferentAdmin() {
        assertTrue(RolePolicy.canApproveRoleGrant(FixedFixtures.X, FixedFixtures.Y,
                Set.of(RoleCode.ADMIN), RoleCode.REVIEWER_L1));
        assertFalse(RolePolicy.canApproveRoleGrant(FixedFixtures.X, FixedFixtures.X,
                Set.of(RoleCode.ADMIN), RoleCode.REVIEWER_L1));
        assertFalse(RolePolicy.canApproveRoleGrant(FixedFixtures.X, FixedFixtures.O,
                Set.of(RoleCode.OPERATOR), RoleCode.REVIEWER_L1));
        assertFalse(RolePolicy.canApproveRoleGrant(FixedFixtures.X, FixedFixtures.Y,
                Set.of(RoleCode.ADMIN, RoleCode.REVIEWER_L1), RoleCode.AUTHOR));
    }

    @Test
    void canDecideCaseEnforcesIndependenceAndReviewStage() {
        assertTrue(RolePolicy.canDecideCase(FixedFixtures.L1A, Set.of(RoleCode.REVIEWER_L1),
                Set.of(), false, 1, null));
        assertFalse(RolePolicy.canDecideCase(FixedFixtures.L1A, Set.of(RoleCode.REVIEWER_L1),
                Set.of(FixedFixtures.L1A), false, 1, null), "原审回避");
        assertFalse(RolePolicy.canDecideCase(FixedFixtures.L1A, Set.of(RoleCode.REVIEWER_L1),
                Set.of(), false, 2, FixedFixtures.L1A), "双审两席同主体");
        assertFalse(RolePolicy.canDecideCase(FixedFixtures.L1A, Set.of(RoleCode.REVIEWER_L1),
                Set.of(), true, 2, null), "高风险二审须 L2");
        assertTrue(RolePolicy.canDecideCase(FixedFixtures.L2A, Set.of(RoleCode.REVIEWER_L2),
                Set.of(), true, 2, null));
        assertFalse(RolePolicy.canDecideCase(FixedFixtures.L1A, Set.of(RoleCode.REVIEWER_L1),
                Set.of(), true, 3, null), "阶段 3 须全新 L2");
        assertTrue(RolePolicy.canDecideCase(FixedFixtures.L2B, Set.of(RoleCode.REVIEWER_L2),
                Set.of(), true, 3, null));
        assertFalse(RolePolicy.canDecideCase(FixedFixtures.A,
                Set.of(RoleCode.AUTHOR, RoleCode.REVIEWER_L1), Set.of(), false, 1, null));
    }

    @Test
    void canReviewAppealRequiresIndependentL2OrLegal() {
        assertTrue(RolePolicy.canReviewAppeal(FixedFixtures.L2B,
                Set.of(RoleCode.REVIEWER_L2), Set.of(FixedFixtures.L1A)));
        assertTrue(RolePolicy.canReviewAppeal(FixedFixtures.K,
                Set.of(RoleCode.LEGAL), Set.of(FixedFixtures.L1A)));
        assertFalse(RolePolicy.canReviewAppeal(FixedFixtures.L1A,
                Set.of(RoleCode.REVIEWER_L2), Set.of(FixedFixtures.L1A)));
        assertFalse(RolePolicy.canReviewAppeal(FixedFixtures.A,
                Set.of(RoleCode.AUTHOR), Set.of()));
    }

    @Test
    void roleAssignmentExpiryIsPure() {
        RoleAssignment active = new RoleAssignment(FixedFixtures.A, RoleCode.AUTHOR,
                ScopeType.WORK, FixedFixtures.W, NOW.plusSeconds(15));
        assertTrue(active.isActiveAt(NOW));
        assertFalse(active.isActiveAt(NOW.plusSeconds(16)));
        RoleAssignment noExpiry = new RoleAssignment(FixedFixtures.A, RoleCode.AUTHOR,
                ScopeType.WORK, FixedFixtures.W, null);
        assertTrue(noExpiry.isActiveAt(NOW));
    }

    /** 补充：枚举辅助短路、全部空值防护与多角色表驱动短路分支。 */
    @Test
    void roleHelpersAndNullGuardsAreExplicit() {
        for (RoleCode role : RoleCode.values()) {
            boolean expectedReview = role == RoleCode.REVIEWER_L1 || role == RoleCode.REVIEWER_L2;
            boolean expectedMutex = role == RoleCode.AUTHOR || role == RoleCode.OPERATOR;
            assertEquals(expectedReview, role.isReviewRole());
            assertEquals(expectedMutex, role.isAccountMutexWithReview());
        }
        assertTrue(RolePolicy.validateRoleSet(null).compatible());
        assertFalse(RolePolicy.canAssign(null, null));
        assertTrue(RolePolicy.canAssign(null, RoleCode.READER));
        assertFalse(RolePolicy.authorize(null).allowed());
        assertFalse(RolePolicy.canApproveRoleGrant(null, FixedFixtures.Y,
                Set.of(RoleCode.ADMIN), RoleCode.READER));
        assertFalse(RolePolicy.canApproveRoleGrant(FixedFixtures.X, null,
                Set.of(RoleCode.ADMIN), RoleCode.READER));
        assertFalse(RolePolicy.canApproveRoleGrant(FixedFixtures.X, FixedFixtures.Y,
                null, RoleCode.READER));
        assertTrue(RolePolicy.canApproveRoleGrant(FixedFixtures.X, FixedFixtures.Y,
                Set.of(RoleCode.ADMIN), null));
        assertFalse(RolePolicy.canDecideCase(null, Set.of(RoleCode.REVIEWER_L1),
                Set.of(), false, 1, null));
        assertFalse(RolePolicy.canDecideCase(FixedFixtures.L1A, null,
                Set.of(), false, 1, null));
        assertFalse(RolePolicy.canDecideCase(FixedFixtures.L1A, Set.of(),
                Set.of(), false, 1, null));
        assertFalse(RolePolicy.canDecideCase(FixedFixtures.L1A, Set.of(RoleCode.READER),
                Set.of(), false, 1, null));
        assertFalse(RolePolicy.canReviewAppeal(null, Set.of(RoleCode.REVIEWER_L2), Set.of()));
        assertFalse(RolePolicy.canReviewAppeal(FixedFixtures.L2A, null, Set.of()));
        assertFalse(RolePolicy.canReviewAppeal(FixedFixtures.L2A, Set.of(), Set.of()));
        assertFalse(RolePolicy.canReviewAppeal(FixedFixtures.L2A,
                Set.of(RoleCode.AUTHOR, RoleCode.REVIEWER_L1), Set.of()));
        assertTrue(RolePolicy.authorize(context(FixedFixtures.A,
                Set.of(RoleCode.READER, RoleCode.AUTHOR), Action.WRITE_DRAFT)).allowed());
        assertFalse(new RoleAssignment(FixedFixtures.A, RoleCode.AUTHOR, ScopeType.WORK,
                FixedFixtures.W, NOW.plusSeconds(15)).isActiveAt(null));
        assertTrue(RolePolicy.authorize(context(FixedFixtures.A,
                Set.of(RoleCode.AUTHOR), Action.READ_PUBLIC,
                FixedFixtures.B, null, null, Set.of(), 1, null, null)).allowed());
    }
}
