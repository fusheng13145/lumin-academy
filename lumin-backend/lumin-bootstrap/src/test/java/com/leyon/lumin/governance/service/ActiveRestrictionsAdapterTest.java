package com.leyon.lumin.governance.service;

import com.leyon.lumin.foundation.values.RestrictionKind;
import com.leyon.lumin.governance.repository.RestrictionMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生效限制查询适配器单测：查询必须带当前时间，过期临时限制由 SQL 过滤（04 §5）。
 */
class ActiveRestrictionsAdapterTest {

    private final RestrictionMapper mapper = mock(RestrictionMapper.class);

    @Test
    void workScopeQueryPassesNowAndMapsKind() {
        when(mapper.selectActiveKinds(eq("work-1"), isNull(), any(LocalDateTime.class)))
                .thenReturn(List.of("TEMPORARY"));

        assertThat(new ActiveRestrictionsAdapter(mapper)
                .activeKinds("WORK", "work-1", null))
                .containsExactly(RestrictionKind.TEMPORARY);
        verify(mapper).selectActiveKinds(eq("work-1"), isNull(), any(LocalDateTime.class));
    }

    @Test
    void chapterScopeQueryKeepsChapterId() {
        when(mapper.selectActiveKinds(eq("work-1"), eq("chapter-1"), any(LocalDateTime.class)))
                .thenReturn(List.of("GUARDIAN_REVOKED"));

        assertThat(new ActiveRestrictionsAdapter(mapper)
                .activeKinds("CHAPTER", "work-1", "chapter-1"))
                .containsExactly(RestrictionKind.GUARDIAN_REVOKED);
        verify(mapper).selectActiveKinds(eq("work-1"), eq("chapter-1"), any(LocalDateTime.class));
    }
}
