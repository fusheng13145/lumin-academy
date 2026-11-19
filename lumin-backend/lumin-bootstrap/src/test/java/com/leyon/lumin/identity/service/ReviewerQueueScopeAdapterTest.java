package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.port.ReviewerQueueScopePort;
import com.leyon.lumin.identity.repository.RoleAssignmentMapper;
import com.leyon.lumin.identity.repository.RoleGrantRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 审核队列作用域：GLOBAL / QUEUE / 无绑定在 demo 与 session 下分流。 */
class ReviewerQueueScopeAdapterTest {

    private static final String SUBJECT = "7f7f7f7f-0000-4000-8000-0000000000c3";
    private static final String ROLE = "REVIEWER_L2";

    private final RoleAssignmentMapper mapper = mock(RoleAssignmentMapper.class);

    @Test
    void queueBindingsLimitToTheirQueueIds() {
        ReviewerQueueScopeAdapter adapter = new ReviewerQueueScopeAdapter(mapper, "session");
        when(mapper.findActiveBindings(eq(SUBJECT), any(LocalDateTime.class)))
                .thenReturn(List.of(binding("QUEUE", "queue-1"), binding("QUEUE", "queue-2")));

        ReviewerQueueScopePort.QueueScope scope = adapter.resolve(SUBJECT, ROLE);

        assertFalse(scope.global());
        assertEquals(List.of("queue-1", "queue-2"), scope.queueIds().stream().sorted().toList());
    }

    @Test
    void globalBindingAllowsAllQueues() {
        ReviewerQueueScopeAdapter adapter = new ReviewerQueueScopeAdapter(mapper, "session");
        when(mapper.findActiveBindings(eq(SUBJECT), any(LocalDateTime.class)))
                .thenReturn(List.of(binding("GLOBAL", null)));

        ReviewerQueueScopePort.QueueScope scope = adapter.resolve(SUBJECT, ROLE);

        assertTrue(scope.global());
    }

    @Test
    void missingBindingFailsClosedInSessionAndStaysUsableInDemo() {
        when(mapper.findActiveBindings(eq(SUBJECT), any(LocalDateTime.class)))
                .thenReturn(List.of());

        ReviewerQueueScopePort.QueueScope session =
                new ReviewerQueueScopeAdapter(mapper, "session").resolve(SUBJECT, ROLE);
        assertFalse(session.global());
        assertTrue(session.queueIds().isEmpty());

        ReviewerQueueScopePort.QueueScope demo =
                new ReviewerQueueScopeAdapter(mapper, "demo").resolve(SUBJECT, ROLE);
        assertTrue(demo.global());
    }

    private RoleGrantRow binding(String scopeType, String scopeId) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 19, 12, 0);
        return new RoleGrantRow("grant-1", SUBJECT, ROLE, scopeType, scopeId,
                null, "测试绑定", "APPROVED", "operator", "admin", now,
                now, now, "operator", "admin", 1L);
    }
}
