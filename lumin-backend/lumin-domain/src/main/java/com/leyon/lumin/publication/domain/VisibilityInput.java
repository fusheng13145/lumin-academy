package com.leyon.lumin.publication.domain;

import com.leyon.lumin.foundation.values.AccountStatus;
import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.values.BusinessState;
import com.leyon.lumin.foundation.values.Grade;
import com.leyon.lumin.foundation.values.RestrictionKind;
import com.leyon.lumin.foundation.values.TargetType;
import com.leyon.lumin.foundation.values.VersionState;
import com.leyon.lumin.foundation.values.WorkflowState;

import java.util.Set;

/**
 * 三态裁决输入（03 §3.1、docs/10 A.3.3）。
 *
 * <p>candidateVersion* / pointer* / boundWorkflow* 全部来自数据库公开指针与权威记录，
 * 由调用方注入；<strong>不接受客户端指定「已通过」版本</strong>（INV-07）。
 */
public record VisibilityInput(
        AgeBand requesterEffectiveAgeBand,
        AccountStatus accountStatus,
        TargetType targetType,
        String targetId,
        BusinessState targetBusinessState,
        BusinessState parentWorkBusinessState,
        Set<RestrictionKind> activeRestrictions,
        String candidateVersionId,
        VersionState candidateVersionState,
        Grade candidateGrade,
        String boundWorkflowId,
        WorkflowState boundWorkflowState,
        String workflowBoundVersionId,
        long boundPolicyRevision,
        long activePolicyRevision,
        String pointerVersionId,
        String pointerTargetId,
        String versionTargetId,
        boolean materialSha256Matches,
        boolean authoritativeReadOk,
        boolean everPublished,
        boolean adultContentEnabled,
        boolean adultEntryConfirmed) {
}
