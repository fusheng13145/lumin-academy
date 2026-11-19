package com.leyon.lumin.engagement.service;

import com.leyon.lumin.engagement.repository.NotificationMapper;
import com.leyon.lumin.engagement.repository.NotificationRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.port.NotificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String RECIPIENT = "recipient-1";
    private static final String NOTIFICATION = "notification-1";

    @Mock
    private NotificationMapper notificationMapper;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notificationMapper);
    }

    private NotificationPort.NotificationRequest request(String recipient, String category) {
        return new NotificationPort.NotificationRequest(recipient, category, "event-1",
                "标题", "正文", "WORK", "work-1");
    }

    @Test
    void createRejectsMissingOrUnknownCategory() {
        assertCode(ErrorCode.P1001, () -> service.create(null));
        assertCode(ErrorCode.P1001, () -> service.create(request(null, "REVIEW_RESULT")));
        assertCode(ErrorCode.P1001, () -> service.create(request(RECIPIENT, null)));
        assertCode(ErrorCode.P1001, () -> service.create(request(RECIPIENT, "OTHER")));
    }

    @Test
    void createReturnsExistingOrPersists() {
        when(notificationMapper.findIdByKey(RECIPIENT, "event-1", "REVIEW_RESULT"))
                .thenReturn("existing");
        assertEquals("existing", service.create(request(RECIPIENT, "REVIEW_RESULT")));
        verify(notificationMapper, never()).insert(any());

        when(notificationMapper.findIdByKey(RECIPIENT, "event-1", "SAFETY")).thenReturn(null);
        String id = service.create(request(RECIPIENT, "SAFETY"));
        assertEquals(true, id.length() > 0);
        verify(notificationMapper).insert(any(NotificationRow.class));
    }

    @Test
    void createRecoversFromConcurrentDuplicate() {
        when(notificationMapper.findIdByKey(anyString(), anyString(), anyString()))
                .thenReturn(null, "winner");
        when(notificationMapper.insert(any(NotificationRow.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        assertEquals("winner", service.create(request(RECIPIENT, "UPDATE")));
    }

    @Test
    void listUnreadDelegates() {
        when(notificationMapper.listUnread(RECIPIENT)).thenReturn(List.of());
        assertEquals(0, service.listUnread(RECIPIENT).size());
    }

    @Test
    void markReadIsIdempotentAndEnforcesOwnership() {
        assertCode(ErrorCode.B2004, () -> service.markRead(RECIPIENT, null));
        assertCode(ErrorCode.B2004, () -> service.markRead(RECIPIENT, NOTIFICATION));

        NotificationRow read = new NotificationRow(NOTIFICATION, RECIPIENT, "SAFETY", "event-1",
                "标题", "正文", "WORK", "work-1", LocalDateTime.now(), LocalDateTime.now(),
                LocalDateTime.now(), "system", "system", 1L);
        when(notificationMapper.findById(NOTIFICATION)).thenReturn(read);
        service.markRead(RECIPIENT, NOTIFICATION);
        verify(notificationMapper, never()).markRead(anyString(), anyString(), any());

        NotificationRow foreign = new NotificationRow(NOTIFICATION, "other", "SAFETY", "event-1",
                "标题", "正文", "WORK", "work-1", null, LocalDateTime.now(),
                LocalDateTime.now(), "system", "system", 1L);
        when(notificationMapper.findById(NOTIFICATION)).thenReturn(foreign);
        assertCode(ErrorCode.B2004, () -> service.markRead(RECIPIENT, NOTIFICATION));

        NotificationRow unread = new NotificationRow(NOTIFICATION, RECIPIENT, "SAFETY", "event-1",
                "标题", "正文", "WORK", "work-1", null, LocalDateTime.now(),
                LocalDateTime.now(), "system", "system", 1L);
        when(notificationMapper.findById(NOTIFICATION)).thenReturn(unread);
        service.markRead(RECIPIENT, NOTIFICATION);
        verify(notificationMapper).markRead(anyString(), anyString(), any());
    }

    private static void assertCode(ErrorCode expected,
                                   org.junit.jupiter.api.function.Executable action) {
        LuminBusinessException error = assertThrows(LuminBusinessException.class, action);
        assertEquals(expected, error.code());
    }
}
