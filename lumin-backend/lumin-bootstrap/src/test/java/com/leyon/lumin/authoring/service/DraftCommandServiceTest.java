package com.leyon.lumin.authoring.service;

import com.leyon.lumin.authoring.repository.DraftMapper;
import com.leyon.lumin.authoring.repository.DraftRow;
import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.web.ReceiptView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DraftCommandServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);

    @Mock
    private DraftMapper draftMapper;
    @Mock
    private WorkService workService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private AuditRecorder auditRecorder;

    private DraftCommandService service;

    @BeforeEach
    void setUp() {
        service = new DraftCommandService(draftMapper, workService, objectMapper,
                auditRecorder, "test-cursor-key-32-bytes");
    }

    private DraftRow row(String state, long lock) {
        return new DraftRow("d1", "w1", null, "WORK", null, "标题", "简介", null, "[]",
                null, null, state, NOW, NOW, "s1", "s1", lock, null);
    }

    private void owned() {
        when(workService.requireOwnedWork("w1", "s1"))
                .thenReturn(new WorkService.WorkSummary("w1", "s1", "VALID", "ACTIVE", 1L));
    }

    @Test
    void discardEditableDraftWithMatchingLock() {
        when(draftMapper.findById("d1")).thenReturn(row("EDITABLE", 3L));
        owned();
        when(draftMapper.discard(anyString(), anyLong(), anyString(), any())).thenReturn(1);

        ReceiptView receipt = service.discardDraft("s1", "d1", "\"3\"", "不再需要");

        assertEquals("DISCARDED", receipt.state());
        assertEquals("DRAFT", receipt.resourceType());
        assertEquals(4L, receipt.revision());
    }

    @Test
    void discardFrozenDraftIsStateConflict() {
        when(draftMapper.findById("d1")).thenReturn(row("SUBMITTED", 3L));
        owned();

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.discardDraft("s1", "d1", "\"3\"", "不再需要"));
        assertEquals(ErrorCode.B2001, error.code());
    }

    @Test
    void discardStaleIfMatchIsPreconditionFailure() {
        when(draftMapper.findById("d1")).thenReturn(row("EDITABLE", 3L));
        owned();

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.discardDraft("s1", "d1", "\"2\"", "不再需要"));
        assertEquals(ErrorCode.B2002, error.code());
    }

    @Test
    void discardEmptyReasonIsInvalidPayload() {
        when(draftMapper.findById("d1")).thenReturn(row("EDITABLE", 3L));
        owned();

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.discardDraft("s1", "d1", "\"3\"", "  "));
        assertEquals(ErrorCode.P1001, error.code());
    }
}
