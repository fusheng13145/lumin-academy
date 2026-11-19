package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.port.ReviewerQueueScopePort;
import com.leyon.lumin.identity.repository.RoleAssignmentMapper;
import com.leyon.lumin.identity.repository.RoleGrantRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 审核队列作用域适配器（05 §3.3）：从 role_assignment 读取主体当前角色绑定，
 * GLOBAL 放行全部队列，QUEUE 仅放行绑定 scope_id；无匹配绑定时——
 * demo 模式保留骨架替身可读，session 模式按空作用域失败关闭。
 */
@Service
public class ReviewerQueueScopeAdapter implements ReviewerQueueScopePort {

    private final RoleAssignmentMapper roleAssignmentMapper;
    private final String authMode;

    public ReviewerQueueScopeAdapter(
            RoleAssignmentMapper roleAssignmentMapper,
            @Value("${lumin.auth.mode:demo}") String authMode) {
        this.roleAssignmentMapper = roleAssignmentMapper;
        this.authMode = authMode;
    }

    @Override
    @Transactional(readOnly = true)
    public QueueScope resolve(String subjectId, String role) {
        if (subjectId == null || subjectId.isBlank() || role == null || role.isBlank()) {
            return QueueScope.none();
        }
        List<RoleGrantRow> bindings = roleAssignmentMapper.findActiveBindings(
                subjectId, LocalDateTime.now(ZoneOffset.UTC));
        List<RoleGrantRow> matching = bindings.stream()
                .filter(binding -> role.equals(binding.role()))
                .toList();
        if (matching.isEmpty()) {
            // 演示替身没有真实 role_assignment；生产 session 模式禁止无绑定放行
            return "demo".equalsIgnoreCase(authMode) ? QueueScope.all() : QueueScope.none();
        }
        if (matching.stream().anyMatch(binding -> "GLOBAL".equals(binding.scopeType()))) {
            return QueueScope.all();
        }
        Set<String> queueIds = new HashSet<>();
        for (RoleGrantRow binding : matching) {
            if ("QUEUE".equals(binding.scopeType())
                    && binding.scopeId() != null && !binding.scopeId().isBlank()) {
                queueIds.add(binding.scopeId());
            }
        }
        return queueIds.isEmpty() ? QueueScope.none() : new QueueScope(false, queueIds);
    }
}
