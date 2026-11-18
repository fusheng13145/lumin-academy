package com.leyon.lumin.engagement.service;

import com.leyon.lumin.foundation.port.NotificationPort;
import org.springframework.stereotype.Service;

/** 通知端口适配器（engagement 域所有权：notification 唯一写入者）。 */
@Service
public class NotificationPortAdapter implements NotificationPort {

    private final NotificationService notificationService;

    public NotificationPortAdapter(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public String create(NotificationRequest request) {
        return notificationService.create(request);
    }
}
