package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.foundation.crypto.FieldCipher;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import com.leyon.lumin.identity.port.GuardianVerificationPort;
import com.leyon.lumin.identity.repository.GuardianConsentMapper;
import com.leyon.lumin.identity.repository.GuardianConsentRow;
import com.leyon.lumin.identity.repository.RoleAssignmentMapper;
import com.leyon.lumin.identity.repository.UserProfileMapper;
import com.leyon.lumin.identity.repository.UserProfileRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 监护同意服务（05 §3.1 create/list/get/revokeConsent；docs/13 UGC-003）：
 * 监护人资格与关系/scope 均由服务端权威记录和受信核验端口回查，禁止客户端
 * 仅凭 childId 绑定；列表/详情仅同意双方可见，管理员不例外；撤销仅同意监护人，
 * 且立即将未成年有效层降为 C4。核验与撤销追加 proposal_review，不覆写历史。
 */
@Service
public class GuardianConsentService {

    private static final String PROOF_REF_AAD = "guardian_consent.proof_ref";
    private static final Set<String> SCOPES = Set.of("REGISTRATION", "PUBLICATION", "PUSH");
    private static final int PROOF_MAX = 200;
    private static final int REASON_MAX = 500;
    private static final String RESOURCE_TYPE = "CONSENT";

    private final GuardianConsentMapper consentMapper;
    private final ProposalReviewStore reviewStore;
    private final RoleAssignmentMapper assignmentMapper;
    private final UserProfileMapper profileMapper;
    private final GuardianVerificationPort verificationPort;
    private final FieldCipher fieldCipher;
    private final String cursorKey;

    public GuardianConsentService(GuardianConsentMapper consentMapper,
                                  ProposalReviewStore reviewStore,
                                  RoleAssignmentMapper assignmentMapper,
                                  UserProfileMapper profileMapper,
                                  GuardianVerificationPort verificationPort,
                                  FieldCipher fieldCipher,
                                  @Value("${lumin.cursor.key}") String cursorKey) {
        this.consentMapper = consentMapper;
        this.reviewStore = reviewStore;
        this.assignmentMapper = assignmentMapper;
        this.profileMapper = profileMapper;
        this.verificationPort = verificationPort;
        this.fieldCipher = fieldCipher;
        this.cursorKey = cursorKey;
    }

    /** 契约 ConsentRequest（原始请求回显；proofRef 仅同意双方可见）。 */
    public record ConsentRequestView(String childId, String proofRef, String scope) {
    }

    /** 契约 ReviewRecord。 */
    public record ReviewView(String id, String actorId, String action, String reason,
                             List<String> evidenceRefs, long reviewedRevision,
                             String createdAt) {
    }

    /** 契约 ConsentView。 */
    public record ConsentView(String id, String createdBy, String createdAt,
                              ConsentRequestView request, long revision, String state,
                              List<ReviewView> reviews) {
    }

    /** 契约 ConsentPage。 */
    public record Page(List<ConsentView> items, String nextCursor) {
    }

    /** 契约 Receipt（resourceType=CONSENT）。 */
    public record Receipt(String id, String resourceType, String state, long revision) {
    }

    @Transactional
    public Receipt create(String subject, String stubRole, String childId, String proofRef,
                          String scope, LocalDateTime now) {
        requireGuardian(subject, stubRole, now);
        String normalizedChild = requireUuid(childId);
        if (subject.equals(normalizedChild)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String normalizedProof = normalizeProofRef(proofRef);
        String normalizedScope = normalizeScope(scope);
        // 监护人与 child 均须有档案（FK 约束）；关系不能靠客户端自报
        UserProfileRow guardianProfile = profileMapper.findById(subject);
        UserProfileRow childProfile = profileMapper.findById(normalizedChild);
        if (guardianProfile == null || childProfile == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        // 监护人须为已核验成年（C3）；未成年/未核验不得代建同意
        if (!"C3".equals(guardianProfile.verifiedAgeBand())) {
            throw new LuminBusinessException(ErrorCode.A2002);
        }
        // 已成年的 child 不需要监护同意；拒绝伪造“监护”关系
        if ("C3".equals(childProfile.verifiedAgeBand())) {
            throw new LuminBusinessException(ErrorCode.A2002);
        }
        if (!verificationPort.verify(subject, normalizedChild, normalizedScope,
                normalizedProof, now)) {
            throw new LuminBusinessException(ErrorCode.A2002);
        }

        String id = UUID.randomUUID().toString();
        String digest = sha256(normalizedProof);
        if (consentMapper.findByProofDigest(digest) != null) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        GuardianConsentRow row = new GuardianConsentRow(id, subject, normalizedChild, digest,
                fieldCipher.encrypt(normalizedProof, PROOF_REF_AAD), normalizedScope,
                now, null, now, now, subject, subject, 1L);
        try {
            consentMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 同一核验凭据/同 scope 有效同意并发：唯一约束兜底
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        appendReview(id, subject, "VERIFY", "监护关系与授权范围核验通过",
                id, 1L, now);
        // 有效同意恢复未成年已核验层（此前可能因撤销降为 C4）；未核验不升级
        profileMapper.restoreAfterConsent(normalizedChild, now, subject);
        return new Receipt(id, RESOURCE_TYPE, "ACTIVE", 1L);
    }

    @Transactional(readOnly = true)
    public Page listVisible(String subject, String cursorToken, Integer limitParam,
                            LocalDateTime now) {
        ErrorCode limitCheck = CursorCodec.validateLimit(limitParam);
        if (limitCheck != ErrorCode.OK) {
            throw new LuminBusinessException(limitCheck);
        }
        int limit = CursorCodec.resolveLimit(limitParam);
        String filterDigest = CursorCodec.filterDigest("list=guardian-consents;viewer=" + subject);
        LocalDateTime cursorAt = null;
        String cursorId = null;
        if (cursorToken != null && !cursorToken.isBlank()) {
            CursorCodec.DecodedCursor decoded = CursorCodec.decode(cursorToken,
                    cursorKey.getBytes(StandardCharsets.UTF_8),
                    now.toInstant(ZoneOffset.UTC), filterDigest);
            if (decoded.code() != ErrorCode.OK) {
                throw new LuminBusinessException(decoded.code());
            }
            if (!subject.equals(decoded.payload().subjectId().toString())) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
            cursorAt = LocalDateTime.parse(decoded.payload().sortKeyValue());
            cursorId = decoded.payload().lastId().toString();
        }
        List<GuardianConsentRow> rows = consentMapper.listVisible(subject, cursorAt, cursorId,
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<GuardianConsentRow> page = rows.subList(0, Math.min(rows.size(), limit));
        List<ConsentView> items = new ArrayList<>(page.size());
        for (GuardianConsentRow row : page) {
            items.add(toView(row));
        }
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            GuardianConsentRow last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(new CursorCodec.Payload(
                    last.grantedAt().toString(), UUID.fromString(last.id()), filterDigest,
                    UUID.fromString(subject), now.toInstant(ZoneOffset.UTC)),
                    cursorKey.getBytes(StandardCharsets.UTF_8));
        }
        return new Page(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public ConsentView getVisible(String subject, String consentId) {
        GuardianConsentRow row = consentId == null ? null : consentMapper.findById(consentId);
        if (row == null || (!subject.equals(row.guardianId())
                && !subject.equals(row.childId()))) {
            // 不存在/越权统一 404，避免猜测 ID 绕过授权
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return toView(row);
    }

    @Transactional
    public Receipt revoke(String subject, String stubRole, String consentId,
                          String reason, long expectedRevision, LocalDateTime now) {
        requireGuardian(subject, stubRole, now);
        String normalizedReason = normalizeReason(reason);
        if (expectedRevision < 1) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        GuardianConsentRow row = consentId == null ? null : consentMapper.findById(consentId);
        if (row == null || !subject.equals(row.guardianId())) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (row.revokedAt() != null) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        long current = row.lockVersion() == null ? 1L : row.lockVersion();
        if (current != expectedRevision) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        int updated = consentMapper.revoke(row.id(), subject, expectedRevision, now, subject);
        if (updated == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        appendReview(row.id(), subject, "REVOKE", normalizedReason, row.id(),
                expectedRevision, now);
        // 撤销立即降级：仅未成年已核验层回落 C4；成年不受影响
        profileMapper.downgradeAfterConsentRevocation(row.childId(), now, subject);
        return new Receipt(row.id(), RESOURCE_TYPE, "REVOKED", expectedRevision + 1);
    }

    private ConsentView toView(GuardianConsentRow row) {
        String proofRef;
        try {
            proofRef = fieldCipher.decrypt(row.proofRefCipher(), PROOF_REF_AAD);
        } catch (IllegalStateException e) {
            throw new LuminBusinessException(ErrorCode.D2001);
        }
        List<ReviewView> reviews = reviewStore.listByResource(RESOURCE_TYPE, row.id())
                .stream()
                .map(r -> new ReviewView(r.id(), r.actorId(), r.action(), r.reason(),
                        r.evidenceRefs(), r.reviewedRevision(),
                        r.eventAt() == null ? null : r.eventAt().toString()))
                .toList();
        return new ConsentView(row.id(), row.guardianId(), row.grantedAt().toString(),
                new ConsentRequestView(row.childId(), proofRef, row.scope()),
                row.lockVersion() == null ? 1L : row.lockVersion(),
                row.revokedAt() == null ? "ACTIVE" : "REVOKED", reviews);
    }

    private void appendReview(String consentId, String actor, String action, String reason,
                              String evidenceId, long reviewedRevision, LocalDateTime now) {
        reviewStore.append(RESOURCE_TYPE, consentId, actor, action, reason,
                List.of("guardian_consent:" + evidenceId), reviewedRevision, now);
    }

    private void requireGuardian(String subject, String stubRole, LocalDateTime now) {
        if (subject == null || subject.isBlank()) {
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        boolean guardian = "GUARDIAN".equalsIgnoreCase(stubRole)
                || assignmentMapper.findActiveRoleCodes(subject, now).stream()
                .anyMatch("GUARDIAN"::equals);
        if (!guardian) {
            // ADMIN 也不越过监护人本人授权；管理员无权代建/代撤同意
            throw new LuminBusinessException(ErrorCode.A2001);
        }
    }

    private static String requireUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
    }

    private static String normalizeProofRef(String proofRef) {
        if (proofRef == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String normalized = proofRef.trim();
        if (normalized.isEmpty() || normalized.length() > PROOF_MAX) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return normalized;
    }

    private static String normalizeScope(String scope) {
        String normalized = scope == null ? "" : scope.trim().toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(normalized)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return normalized;
    }

    private static String normalizeReason(String reason) {
        if (reason == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String normalized = reason.trim();
        if (normalized.isEmpty() || normalized.length() > REASON_MAX) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return normalized;
    }

    private static String sha256(String value) {        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }
}
