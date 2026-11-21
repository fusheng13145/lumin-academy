package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.port.InteractionGatePort;
import com.leyon.lumin.identity.repository.UserBlockMapper;
import com.leyon.lumin.identity.repository.UserBlockRow;
import com.leyon.lumin.identity.repository.UserProfileMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 互动安全应用服务（docs/13 UGC-043 一阶段）：拉黑/解除/列表 + 闸端口。
 * 个人安全自服务——任何已认证主体可拉黑他人（无角色要求）；
 * 不能拉黑自己（400）、目标档案不存在统一 404（不枚举存在性）、
 * 重复拉黑 409；解除幂等。审计留痕（拉黑/解除均为安全事实，追加不删）。
 * 执行面（评论/关注/通知过滤）随 UGC-042/044 经 InteractionGatePort 接线；
 * 未成年保护与频控/反骚扰深化随 UGC-003/UGC-029 批次。
 */
@Service
public class BlockService implements InteractionGatePort {

    private final UserBlockMapper userBlockMapper;
    private final UserProfileMapper userProfileMapper;
    private final AuditRecorder auditRecorder;

    public BlockService(UserBlockMapper userBlockMapper, UserProfileMapper userProfileMapper,
                        AuditRecorder auditRecorder) {
        this.userBlockMapper = userBlockMapper;
        this.userProfileMapper = userProfileMapper;
        this.auditRecorder = auditRecorder;
    }

    /** 拉黑（个人安全自服务；目标须存在；不能拉黑自己；重复 409）。 */
    @Transactional
    public void block(String subject, String targetId, String reason, LocalDateTime now) {
        if (targetId == null || targetId.isBlank() || subject.equals(targetId)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        if (userProfileMapper.findById(targetId) == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (reason != null && reason.length() > 100) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        try {
            userBlockMapper.insert(new UserBlockRow(UUID.randomUUID().toString(),
                    subject, targetId, reason, now, subject, now, subject, 1L));
        } catch (DuplicateKeyException e) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, "READER",
                "block.added", "user_block", targetId, "拉黑用户",
                traceId(), now.toInstant(ZoneOffset.UTC)));
    }

    /** 解除拉黑（幂等；跨账号隔离——仅删除本人拉黑行）。 */
    @Transactional
    public void unblock(String subject, String targetId, LocalDateTime now) {
        if (targetId == null || targetId.isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        userBlockMapper.delete(subject, targetId);
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, "READER",
                "block.removed", "user_block", targetId, "解除拉黑",
                traceId(), now.toInstant(ZoneOffset.UTC)));
    }

    /** 拉黑列表（仅本人可见——不默认公开关系图）。 */
    @Transactional(readOnly = true)
    public List<UserBlockMapper.BlockListItem> listMine(String subject) {
        return userBlockMapper.listByBlocker(subject, 100);
    }

    /** 闸裁决（端口实现；通知过滤/评论/关注接线用）。 */
    @Override
    @Transactional(readOnly = true)
    public boolean isBlocked(String blockerId, String subjectId) {
        return userBlockMapper.isBlocked(blockerId, subjectId);
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.contract.ApiEnvelope.currentTraceId();
    }
}
