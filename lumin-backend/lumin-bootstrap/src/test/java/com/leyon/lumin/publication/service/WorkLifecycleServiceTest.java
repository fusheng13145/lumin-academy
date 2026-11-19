package com.leyon.lumin.publication.service;

import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.web.ReceiptView;
import com.leyon.lumin.publication.repository.PublicationWorkflowMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkLifecycleServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);

    @Mock
    private WorkService workService;
    @Mock
    private PublicationWorkflowMapper workflowMapper;
    @InjectMocks
    private WorkLifecycleService service;

    private WorkService.WorkSummary work(String state, long lock) {
        return new WorkService.WorkSummary("w1", "s1", "VALID", state, lock);
    }

    @Test
    void completeActiveWorkWithNoPendingWorkflow() {
        when(workService.requireOwnedWork("w1", "s1")).thenReturn(work("ACTIVE", 3L));
        when(workflowMapper.countOpenByWork("w1")).thenReturn(0);
        when(workService.allChaptersPublished("w1")).thenReturn(true);
        when(workService.changeBusinessState(anyString(), anyString(), anyString(),
                anyBoolean(), anyLong(), any())).thenReturn(4L);

        ReceiptView receipt = service.change("s1", "w1", "COMPLETE", "\"3\"", "完结",
                NOW);

        assertEquals("COMPLETED", receipt.state());
        assertEquals("WORK", receipt.resourceType());
        assertEquals(4L, receipt.revision());
    }

    @Test
    void completeRejectedWhenWorkflowPendingOrChapterUnpublished() {
        when(workService.requireOwnedWork("w1", "s1")).thenReturn(work("ACTIVE", 3L));
        when(workflowMapper.countOpenByWork("w1")).thenReturn(1);
        when(workService.allChaptersPublished("w1")).thenReturn(false);

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.change("s1", "w1", "COMPLETE", "\"3\"", "完结", NOW));
        assertEquals(ErrorCode.B2001, error.code());
    }

    @Test
    void staleIfMatchAndMissingReasonAreRejectedBeforeTransition() {
        when(workService.requireOwnedWork("w1", "s1")).thenReturn(work("ACTIVE", 3L));

        LuminBusinessException stale = assertThrows(LuminBusinessException.class,
                () -> service.change("s1", "w1", "COMPLETE", "\"2\"", "完结", NOW));
        assertEquals(ErrorCode.B2002, stale.code());

        LuminBusinessException reason = assertThrows(LuminBusinessException.class,
                () -> service.change("s1", "w1", "COMPLETE", "\"3\"", "  ", NOW));
        assertEquals(ErrorCode.P1001, reason.code());
    }

    @Test
    void reopenCompletedWorkRequiresReason() {
        when(workService.requireOwnedWork("w1", "s1")).thenReturn(work("COMPLETED", 5L));
        when(workflowMapper.countOpenByWork("w1")).thenReturn(0);
        when(workService.allChaptersPublished("w1")).thenReturn(true);
        when(workService.changeBusinessState(anyString(), anyString(), anyString(),
                anyBoolean(), anyLong(), any())).thenReturn(6L);

        ReceiptView receipt = service.change("s1", "w1", "REOPEN", "\"5\"", "恢复连载",
                NOW);

        assertEquals("ACTIVE", receipt.state());
        assertEquals(6L, receipt.revision());
    }
}
