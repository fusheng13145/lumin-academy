package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.CategoryMapper;
import com.leyon.lumin.catalog.repository.CategoryRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** UGC-039 分类读取：仅启用分类、limit+1 判定下一页、非法 limit 拒绝。 */
class CategoryServiceTest {

    private static final String SUBJECT = "9f8f8f8f-0000-4000-8000-0000000000a1";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 0, 0);

    private final CategoryMapper categoryMapper = mock(CategoryMapper.class);
    private final CategoryService service = new CategoryService(categoryMapper, "unit-test-key");

    @Test
    void listReturnsFirstPageAndNextCursor() {
        when(categoryMapper.listEnabled(isNull(), isNull(), eq(2))).thenReturn(List.of(
                row("11111111-1111-4111-8111-111111111111", 1),
                row("22222222-2222-4222-8222-222222222222", 2)));

        var page = service.list(SUBJECT, null, 1, NOW);

        assertEquals(1, page.items().size());
        assertEquals("11111111-1111-4111-8111-111111111111",
                page.items().get(0).id());
        assertNotNull(page.nextCursor());
    }

    @Test
    void invalidLimitIsRejected() {
        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.list(SUBJECT, null, 0, NOW));

        assertEquals(ErrorCode.P1001, error.code());
    }

    private CategoryRow row(String id, int sortOrder) {
        return new CategoryRow(id, null, "分类" + sortOrder, sortOrder, true,
                NOW, NOW, "system", "system", 1L, null);
    }
}
