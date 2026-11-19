package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.CategoryMapper;
import com.leyon.lumin.catalog.repository.CategoryRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 分类读取（05 §3.2 listCategories）：仅启用分类，sort_order/id keyset 分页。 */
@Service
public class CategoryService {

    private final CategoryMapper categoryMapper;
    private final String cursorKey;

    public CategoryService(CategoryMapper categoryMapper,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${lumin.cursor.key}") String cursorKey) {
        this.categoryMapper = categoryMapper;
        this.cursorKey = cursorKey;
    }

    /** 契约 Category。 */
    public record CategoryView(String id, String name, String parentId) {
    }

    /** 契约 CategoryPage。 */
    public record Page(List<CategoryView> items, String nextCursor) {
    }

    @Transactional(readOnly = true)
    public Page list(String subject, String cursorToken, Integer limitParam,
                     LocalDateTime now) {
        ErrorCode limitCheck = CursorCodec.validateLimit(limitParam);
        if (limitCheck != ErrorCode.OK) {
            throw new LuminBusinessException(limitCheck);
        }
        int limit = CursorCodec.resolveLimit(limitParam);
        String filterDigest = CursorCodec.filterDigest(
                "list=categories;viewer=" + subject);
        Integer cursorSort = null;
        String cursorId = null;
        if (cursorToken != null && !cursorToken.isBlank()) {
            CursorCodec.DecodedCursor decoded = CursorCodec.decode(cursorToken,
                    cursorKey.getBytes(StandardCharsets.UTF_8),
                    now.toInstant(ZoneOffset.UTC), filterDigest);
            if (decoded.code() != ErrorCode.OK) {
                throw new LuminBusinessException(decoded.code());
            }
            if (!subject.equals(decoded.payload().subjectId().toString())) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
            try {
                cursorSort = Integer.parseInt(decoded.payload().sortKeyValue());
            } catch (NumberFormatException e) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
            cursorId = decoded.payload().lastId().toString();
        }
        List<CategoryRow> rows = categoryMapper.listEnabled(cursorSort, cursorId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<CategoryRow> page = rows.subList(0, Math.min(rows.size(), limit));
        List<CategoryView> items = new ArrayList<>(page.size());
        for (CategoryRow row : page) {
            items.add(new CategoryView(row.id(), row.name(), row.parentId()));
        }
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            CategoryRow last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(new CursorCodec.Payload(
                    String.valueOf(last.sortOrder()), UUID.fromString(last.id()),
                    filterDigest, UUID.fromString(subject),
                    now.toInstant(ZoneOffset.UTC)),
                    cursorKey.getBytes(StandardCharsets.UTF_8));
        }
        return new Page(items, nextCursor);
    }
}
