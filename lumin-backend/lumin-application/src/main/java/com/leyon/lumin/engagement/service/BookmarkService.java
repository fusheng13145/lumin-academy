package com.leyon.lumin.engagement.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.engagement.repository.BookmarkListItem;
import com.leyon.lumin.engagement.repository.BookmarkMapper;
import com.leyon.lumin.engagement.repository.BookmarkRow;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 收藏书架应用服务（docs/04 §6.1 bookmark；docs/13 UGC-035 M1 一阶段）：
 * 收藏/取消收藏/书架列表。收藏幂等（重复收藏无副作用）；删除按主体+作品
 * （跨账号隔离）；列表含可读状态（下架/无公开指针 → 不可读，前端灰显）；
 * 无通知语义（追更走 subscription，UGC-036）；不做自动开启。
 */
@Service
public class BookmarkService {

    private final BookmarkMapper bookmarkMapper;
    private final AuditRecorder auditRecorder;

    public BookmarkService(BookmarkMapper bookmarkMapper, AuditRecorder auditRecorder) {
        this.bookmarkMapper = bookmarkMapper;
        this.auditRecorder = auditRecorder;
    }

    /** 收藏（幂等；重复收藏无副作用、不报错）。 */
    @Transactional
    public void add(String subject, String workId, LocalDateTime now) {
        BookmarkRow row = new BookmarkRow(UUID.randomUUID().toString(), subject, workId,
                now, now, subject, subject, 1L);
        bookmarkMapper.insertIgnore(row);
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, "READER",
                "bookmark.added", "bookmark", workId, "加入书架",
                traceId(), now.toInstant(ZoneOffset.UTC)));
    }

    /** 取消收藏（幂等；跨账号隔离——仅删除本人书架行）。 */
    @Transactional
    public void remove(String subject, String workId, LocalDateTime now) {
        bookmarkMapper.deleteByUserAndWork(subject, workId);
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, "READER",
                "bookmark.removed", "bookmark", workId, "移出书架",
                traceId(), now.toInstant(ZoneOffset.UTC)));
    }

    /** 是否已收藏（书架/详情页标记）。 */
    @Transactional(readOnly = true)
    public boolean isBookmarked(String subject, String workId) {
        return bookmarkMapper.exists(subject, workId) > 0;
    }

    /** 书架列表（收藏时间倒序；含可读状态字段；隐藏总数不泄露）。 */
    @Transactional(readOnly = true)
    public List<BookmarkListItem> listMine(String subject) {
        return bookmarkMapper.listByUser(subject, 100);
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.contract.ApiEnvelope.currentTraceId();
    }
}
