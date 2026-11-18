package com.leyon.lumin.engagement.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.port.NotificationPort;
import com.leyon.lumin.engagement.repository.NotificationMapper;
import com.leyon.lumin.engagement.repository.NotificationRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 站内通知应用服务（02 §3.1 engagement 所有权；实现 foundation 通知端口）。
 * 重复消费经 U(recipient,source_event,category) 幂等（T33：每接收者/源事件/类别
 * 仅 1 条站内结果通知）；正文为模板生成的确定文案（03 §5.1 N6）。
 */
@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;

    public NotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    @Transactional
    public String create(NotificationPort.NotificationRequest request) {
        if (request == null || request.recipientId() == null || request.category() == null
                || !request.category().matches("REVIEW_RESULT|SAFETY|GUARDIAN|RIGHTS|UPDATE")) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String existing = notificationMapper.findIdByKey(request.recipientId(),
                request.sourceEventId(), request.category());
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        NotificationRow row = new NotificationRow(UUID.randomUUID().toString(),
                request.recipientId(), request.category(), request.sourceEventId(),
                request.title(), request.body(), request.resourceType(), request.resourceId(),
                null, now, now, "system", "system", 1L);
        try {
            notificationMapper.insert(row);
            return row.id();
        } catch (DuplicateKeyException e) {
            // 并发重复消费：返回已有通知（幂等，不重复执行）
            return notificationMapper.findIdByKey(request.recipientId(),
                    request.sourceEventId(), request.category());
        }
    }

    /** 本人未读通知列表（05 §3.4 listNotifications：REVIEW_RESULT/SAFETY/GUARDIAN/RIGHTS）。 */
    @Transactional(readOnly = true)
    public List<NotificationRow> listUnread(String recipientId) {
        return notificationMapper.listUnread(recipientId);
    }

    /** 标记已读（T33 幂等：本人已读 → 无副作用成功返回；不存在/非本人 → 404）。 */
    @Transactional
    public void markRead(String recipientId, String notificationId) {
        NotificationRow row = notificationId == null ? null
                : notificationMapper.findById(notificationId);
        if (row == null || !recipientId.equals(row.recipientId())) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (row.readAt() != null) {
            // 重复已读：幂等返回，不重复产生副作用
            return;
        }
        notificationMapper.markRead(notificationId, recipientId,
                LocalDateTime.now(ZoneOffset.UTC));
    }
}
