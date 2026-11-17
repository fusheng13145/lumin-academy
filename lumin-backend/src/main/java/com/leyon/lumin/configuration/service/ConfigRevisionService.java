package com.leyon.lumin.configuration.service;

import com.leyon.lumin.configuration.domain.ConfigPayloadValidator;
import com.leyon.lumin.configuration.domain.ConfigRevisionStateMachine;
import com.leyon.lumin.configuration.domain.GrayRolloutPolicy;
import com.leyon.lumin.configuration.repository.ConfigRevisionMapper;
import com.leyon.lumin.configuration.repository.ConfigRevisionRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.values.ConfigKey;
import com.leyon.lumin.foundation.values.ConfigRevisionEvent;
import com.leyon.lumin.foundation.values.ConfigRevisionState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 配置修订应用服务（03 §2.4 config_revision / 05 §3.4 §4.2；T30–T32）：
 * 提案（OPERATOR/REVIEWER_L2，初建即 IN_REVIEW，payload 类型化 + 最低合规底线）
 * → 独立 ADMIN 审批（申请人不得自批，REVIEW_ROUTING/ACCESS_POLICY 批准须法务确认）
 * → 激活（仅 ADMIN 触发已批准计划；到生效时间才可激活，提前 409；双槽取代旧版置
 * SUPERSEDED）→ 回滚走新提案重复审批（rollback_of 留痕，不改写历史）。
 * payload 审批后冻结：无更新端点（05 §4.2「批后不得替换 payload」）。
 */
@Service
public class ConfigRevisionService {

    /** 可读审查列表的授权角色域（05 §3.4：OPERATOR/ADMIN/REVIEWER 按授权规则域）。 */
    private static final Set<String> READ_ROLES =
            Set.of("OPERATOR", "ADMIN", "REVIEWER_L1", "REVIEWER_L2");
    /** 审批人（独立 ADMIN）与激活触发者。 */
    private static final String ROLE_ADMIN = "ADMIN";
    /** 提案人角色域（OPERATOR 或 REVIEWER_L2）。 */
    private static final Set<String> PROPOSER_ROLES = Set.of("OPERATOR", "REVIEWER_L2");
    /** 批准须完成法务检查的键（审核规则 / 年龄规则）。 */
    private static final Set<ConfigKey> LEGAL_CHECK_KEYS =
            Set.of(ConfigKey.REVIEW_ROUTING, ConfigKey.ACCESS_POLICY);

    private final ConfigRevisionMapper mapper;
    private final AuditRecorder auditRecorder;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public ConfigRevisionService(ConfigRevisionMapper mapper, AuditRecorder auditRecorder,
                                 tools.jackson.databind.ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    /** 提案（初建即 IN_REVIEW）；payload 类型化 + 最低合规底线校验（T31）。 */
    @Transactional
    public String propose(String subject, String stubRole, String configKey, String payloadJson,
                          String reason, LocalDateTime effectiveAt, Integer rolloutBasisPoints,
                          String rollbackOf) {
        requireRole(stubRole, PROPOSER_ROLES);
        if (reason == null || reason.isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        ConfigKey key = parseKey(configKey);
        if (rolloutBasisPoints == null) {
            rolloutBasisPoints = GrayRolloutPolicy.FULL_ROLLOUT_BASIS_POINTS;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ConfigRevisionRow baseline = mapper.findActiveFull(key.name());
        // payload 类型化校验（相对 ACTIVE 基线；无基线用默认底线）
        try {
            var payload = objectMapper.readTree(payloadJson == null ? "{}" : payloadJson);
            var baselinePayload = baseline == null ? null
                    : objectMapper.readTree(baseline.payload());
            if (ConfigPayloadValidator.validateProposal(key, payload, baselinePayload)
                    != ErrorCode.OK) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
        } catch (LuminBusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        // 灰度口径（05 §4.2）：全量键固定 10000；灰度需已有全量基线；比例 1–9999
        var rolloutCheck = GrayRolloutPolicy.validateActivation(key, rolloutBasisPoints,
                baseline != null);
        if (rolloutCheck != ErrorCode.OK) {
            throw new LuminBusinessException(rolloutCheck);
        }
        // 回滚走新提案重复审批：payload 取回滚目标冻结副本，不改写历史（T32）
        String payloadFrozen = payloadJson == null ? "{}" : payloadJson;
        long baseRevision = baseline == null ? 0L : baseline.revision();
        if (rollbackOf != null) {
            ConfigRevisionRow target = mapper.findById(rollbackOf);
            if (target == null || !target.configKey().equals(key.name())) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
            payloadFrozen = target.payload();
            baseRevision = target.revision();
        }
        long revision = mapper.nextRevision(key.name());
        ConfigRevisionRow row = new ConfigRevisionRow(UUID.randomUUID().toString(),
                key.name(), revision, baseRevision, 1, payloadFrozen, "IN_REVIEW", reason,
                subject, null, effectiveAt, rolloutBasisPoints, rollbackOf,
                now, now, subject, subject, 1L);
        mapper.insert(row);
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                "config_revision.proposed", "config_revision", row.id(),
                reason + "（base=" + baseRevision + (rollbackOf == null ? ""
                        : "，回滚自 " + rollbackOf) + "）",
                traceId(), now.toInstant(ZoneOffset.UTC)));
        return row.id();
    }

    /** 独立 ADMIN 审批（申请人不得自批；批准须法务确认；If-Match 乐观锁）。 */
    @Transactional
    public String review(String subject, String stubRole, String id, String ifMatch,
                         boolean approve, String reason, boolean legalConfirmation) {
        requireRole(stubRole, Set.of(ROLE_ADMIN));
        long expectedLock = com.leyon.lumin.foundation.web.IfMatch.requireValid(ifMatch);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ConfigRevisionRow row = mapper.findByIdForUpdate(id);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (subject.equals(row.requestedBy())) {
            // 05 §4.2：申请人与审批人不同（DB ck_config_reviewer 同口径兜底）
            throw new LuminBusinessException(ErrorCode.A2001);
        }
        if (expectedLock != row.lockVersion()) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        ConfigKey key = ConfigKey.valueOf(row.configKey());
        if (approve && LEGAL_CHECK_KEYS.contains(key) && !legalConfirmation) {
            // 审核/年龄规则批准须完成法务检查（05 §3.4「必要合规证明」）
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        var transition = ConfigRevisionStateMachine.transit(
                ConfigRevisionState.valueOf(row.state()),
                approve ? ConfigRevisionEvent.APPROVE : ConfigRevisionEvent.REJECT);
        if (!transition.accepted()) {
            throw new LuminBusinessException(transition.code());
        }
        if (mapper.transition(id, transition.targetState().name(),
                approve ? subject : null, row.state(), row.lockVersion(), subject, now) == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                "config_revision." + (approve ? "approved" : "rejected"),
                "config_revision", id, reason == null ? "" : reason,
                traceId(), now.toInstant(ZoneOffset.UTC)));
        return transition.targetState().name();
    }

    /** 激活：仅 ADMIN 触发已批准计划；到生效时间才可（提前 409）；双槽取代。 */
    @Transactional
    public String activate(String subject, String stubRole, String id) {
        requireRole(stubRole, Set.of(ROLE_ADMIN));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ConfigRevisionRow row = mapper.findByIdForUpdate(id);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        var transition = ConfigRevisionStateMachine.transit(
                ConfigRevisionState.valueOf(row.state()), ConfigRevisionEvent.ACTIVATE);
        if (!transition.accepted()) {
            throw new LuminBusinessException(transition.code());
        }
        if (row.effectiveAt().isAfter(now)) {
            // T30：提前激活 409（ACTIVE 只在到时出现）
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        ConfigKey key = ConfigKey.valueOf(row.configKey());
        ConfigRevisionRow fullBaseline = mapper.findActiveFull(key.name());
        var rolloutCheck = GrayRolloutPolicy.validateActivation(key, row.rolloutBasisPoints(),
                fullBaseline != null
                        || row.rolloutBasisPoints() == GrayRolloutPolicy.FULL_ROLLOUT_BASIS_POINTS);
        if (rolloutCheck != ErrorCode.OK) {
            throw new LuminBusinessException(rolloutCheck);
        }
        // 取代旧版：全量激活同时取代原全量及灰度；灰度激活取代原灰度（至多一个）
        ConfigRevisionRow activeGray = mapper.findActiveGray(key.name());
        if (activeGray != null && !activeGray.id().equals(id)) {
            supersede(activeGray, subject, now);
        }
        if (row.rolloutBasisPoints() == GrayRolloutPolicy.FULL_ROLLOUT_BASIS_POINTS
                && fullBaseline != null && !fullBaseline.id().equals(id)) {
            supersede(fullBaseline, subject, now);
        }
        if (mapper.transition(id, transition.targetState().name(), null,
                row.state(), row.lockVersion(), subject, now) == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                "config_revision.activated", "config_revision", id,
                "激活 revision=" + row.revision() + " rollout=" + row.rolloutBasisPoints(),
                traceId(), now.toInstant(ZoneOffset.UTC)));
        return transition.targetState().name();
    }

    /** 审查列表（先过滤后 limit；仅配置授权角色域可见）。 */
    @Transactional(readOnly = true)
    public List<ConfigRevisionRow> list(String stubRole, String key, String state, int limit) {
        requireRole(stubRole, READ_ROLES);
        return mapper.list(key, state, Math.min(Math.max(limit, 1), 100));
    }

    /** 单条审查读取（含原始提案 payload 与审批身份）。 */
    @Transactional(readOnly = true)
    public ConfigRevisionRow get(String stubRole, String id) {
        requireRole(stubRole, READ_ROLES);
        ConfigRevisionRow row = mapper.findById(id);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return row;
    }

    private void supersede(ConfigRevisionRow row, String subject, LocalDateTime now) {
        var transition = ConfigRevisionStateMachine.transit(
                ConfigRevisionState.valueOf(row.state()), ConfigRevisionEvent.SUPERSEDE);
        if (!transition.accepted()) {
            throw new LuminBusinessException(transition.code());
        }
        if (mapper.transition(row.id(), transition.targetState().name(), row.approvedBy(),
                row.state(), row.lockVersion(), subject, now) == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
    }

    private static void requireRole(String stubRole, Set<String> allowed) {
        if (stubRole == null || !allowed.contains(stubRole)) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
    }

    private static ConfigKey parseKey(String configKey) {
        try {
            return ConfigKey.valueOf(configKey);
        } catch (IllegalArgumentException e) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.web.ApiEnvelope.currentTraceId();
    }
}
