package com.leyon.lumin.engagement.api;

import com.leyon.lumin.engagement.repository.BookmarkListItem;
import com.leyon.lumin.engagement.repository.BookmarkRow;
import com.leyon.lumin.engagement.repository.ReadingProgressMapper;
import com.leyon.lumin.engagement.service.BookmarkService;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 书架接入层（docs/13 UGC-035 M1 一阶段；docs/04 §6.1 bookmark）：
 * 收藏/取消收藏/书架列表（含可读状态口径：下架/无公开指针 → 不可读，前端灰显）
 * + 阅读历史（reading_progress 时间倒序）。收藏与历史分开；无通知语义；
 * 不自动开启追更（订阅随 UGC-036）。
 */
@RestController
@RequestMapping("/api/v1/me")
public class BookmarkController {

    private final BookmarkService bookmarkService;
    private final ReadingProgressMapper progressMapper;

    public BookmarkController(BookmarkService bookmarkService,
                              ReadingProgressMapper progressMapper) {
        this.bookmarkService = bookmarkService;
        this.progressMapper = progressMapper;
    }

    /** 收藏（幂等；重复收藏无副作用）。 */
    @PostMapping("/bookmarks")
    public ApiEnvelope add(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestBody AddBookmarkRequest request) {
        bookmarkService.add(subject, request.workId(), LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("workId", request.workId(), "bookmarked", true));
    }

    /** 书架列表（收藏时间倒序；含可读状态口径字段）。 */
    @GetMapping("/bookmarks")
    public ApiEnvelope list(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestParam(defaultValue = "20") int limit) {
        List<BookmarkListItem> items = bookmarkService.listMine(subject);
        return ApiEnvelope.ok(items.stream()
                .map(item -> Map.of(
                        "workId", item.workId(),
                        "title", item.title() == null ? "" : item.title(),
                        "author", item.author() == null ? "" : item.author(),
                        "grade", item.grade() == null ? "" : item.grade(),
                        "workState", item.workState(),
                        "readable", item.readable(),
                        "bookmarkedAt", item.createdAt().toInstant(ZoneOffset.UTC).toString()))
                .toList());
    }

    /** 取消收藏（幂等）。 */
    @DeleteMapping("/bookmarks/{workId}")
    public ApiEnvelope remove(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String workId) {
        bookmarkService.remove(subject, workId, LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("workId", workId, "bookmarked", false));
    }

    /** 是否已收藏（详情页标记）。 */
    @GetMapping("/bookmarks/{workId}/status")
    public ApiEnvelope status(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String workId) {
        return ApiEnvelope.ok(Map.of("workId", workId,
                "bookmarked", bookmarkService.isBookmarked(subject, workId)));
    }

    /** 阅读历史（进度时间倒序；跨账号隔离）。 */
    @GetMapping("/reading-history")
    public ApiEnvelope history(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestParam(defaultValue = "20") int limit) {
        List<ReadingProgressMapper.ReadingHistoryItem> items =
                progressMapper.listHistory(subject, Math.min(Math.max(limit, 1), 100));
        return ApiEnvelope.ok(items.stream()
                .map(item -> Map.ofEntries(
                        Map.entry("chapterId", item.chapterId()),
                        Map.entry("workId", item.workId()),
                        Map.entry("workTitle", item.workTitle() == null ? "" : item.workTitle()),
                        Map.entry("chapterTitle", item.chapterTitle() == null
                                ? "" : item.chapterTitle()),
                        Map.entry("sequenceNo", item.sequenceNo() == null ? 0 : item.sequenceNo()),
                        Map.entry("positionBasisPoints", item.positionBasisPoints() == null
                                ? 0 : item.positionBasisPoints()),
                        Map.entry("readable", item.versionId() != null),
                        Map.entry("updatedAt", item.updatedAt().toInstant(ZoneOffset.UTC)
                                .toString())))
                .toList());
    }

    /** 收藏请求体。 */
    public record AddBookmarkRequest(String workId) {
    }
}
