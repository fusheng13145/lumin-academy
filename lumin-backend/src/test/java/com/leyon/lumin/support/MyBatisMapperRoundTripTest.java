package com.leyon.lumin.support;

import com.leyon.lumin.authoring.repository.ContentVersionMapper;
import com.leyon.lumin.authoring.repository.ContentVersionRow;
import com.leyon.lumin.authoring.repository.DraftMapper;
import com.leyon.lumin.authoring.repository.DraftRow;
import com.leyon.lumin.foundation.repository.IdempotencyRecordMapper;
import com.leyon.lumin.foundation.repository.IdempotencyRow;
import com.leyon.lumin.foundation.repository.OutboxEventMapper;
import com.leyon.lumin.foundation.repository.OutboxEventRow;
import com.leyon.lumin.publication.repository.PublicationPointerMapper;
import com.leyon.lumin.publication.repository.PublicationPointerRow;
import com.leyon.lumin.publication.repository.PublicationWorkflowMapper;
import com.leyon.lumin.publication.repository.PublicationWorkflowRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MyBatis Mapper 往返验证（真实 MySQL lumin_test；T07 持久化面）：
 * 插入 → 回读字段一致 → 状态机字段更新生效；内容不可变列不出现在任何 UPDATE 中。
 */
@SpringBootTest
class MyBatisMapperRoundTripTest extends MySqlIntegrationBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DraftMapper draftMapper;
    @Autowired
    private ContentVersionMapper contentVersionMapper;
    @Autowired
    private PublicationWorkflowMapper workflowMapper;
    @Autowired
    private PublicationPointerMapper pointerMapper;
    @Autowired
    private OutboxEventMapper outboxMapper;
    @Autowired
    private IdempotencyRecordMapper idempotencyMapper;

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    @Test
    void outboxRoundTripAndDeliveryMarking() {
        String id = newId();
        OutboxEventRow row = new OutboxEventRow(id, newId(), "content_version",
                MySqlFixtures.WORK_W, 1L, "CONTENT_SUBMITTED_V1", 1,
                "{\"k\":\"v\"}", MySqlFixtures.BUSINESS_TIME, null, 0, null,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM, 1L);
        assertEquals(1, outboxMapper.insert(row));
        OutboxEventRow loaded = outboxMapper.findById(id);
        assertNotNull(loaded);
        assertEquals("CONTENT_SUBMITTED_V1", loaded.eventType());
        assertEquals(1L, loaded.aggregateRevision());
        assertNull(loaded.deliveredAt());
        assertEquals(0, loaded.attempts());
        // 投递确认后记 delivered_at；重复标记行数为 0（确认前不删事件，03 §4.3）
        assertEquals(1, outboxMapper.markDelivered(id, MySqlFixtures.BUSINESS_TIME.plusSeconds(1),
                MySqlFixtures.SYSTEM));
        assertEquals(0, outboxMapper.markDelivered(id, MySqlFixtures.BUSINESS_TIME.plusSeconds(2),
                MySqlFixtures.SYSTEM));
        assertNotNull(outboxMapper.findById(id).deliveredAt());
    }

    @Test
    void draftRoundTripAndEditableTransition() {
        String id = newId();
        DraftRow row = new DraftRow(id, MySqlFixtures.WORK_W, null, "WORK", null,
                "启明", "简介文本", MySqlFixtures.CATEGORY_LITERATURE, "[\"奇幻\"]",
                null, null, "EDITABLE", MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM, 1L,
                null);
        assertEquals(1, draftMapper.insert(row));
        DraftRow loaded = draftMapper.findById(id);
        assertEquals("启明", loaded.title());
        assertEquals("[\"奇幻\"]", loaded.tags());
        assertEquals("EDITABLE", loaded.state());
        assertEquals(1L, loaded.lockVersion());
        // 03 §2.2：保存递增版本；EDITABLE → SUBMITTED 后原草稿冻结
        assertEquals(1, draftMapper.transitionEditableTo(id, "SUBMITTED",
                MySqlFixtures.SYSTEM, MySqlFixtures.BUSINESS_TIME.plusSeconds(1)));
        DraftRow submitted = draftMapper.findById(id);
        assertEquals("SUBMITTED", submitted.state());
        assertEquals(2L, submitted.lockVersion());
        // 再从 SUBMITTED 迁移：行数为 0（守卫 state='EDITABLE'）
        assertEquals(0, draftMapper.transitionEditableTo(id, "DISCARDED",
                MySqlFixtures.SYSTEM, MySqlFixtures.BUSINESS_TIME.plusSeconds(2)));
    }

    @Test
    void contentVersionStateTransitionNeverTouchesImmutableColumns() {
        String draftId = newId();
        jdbc.update("""
                INSERT IGNORE INTO draft (id, work_id, target_type, title, synopsis,
                    category_id, tags, state, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'WORK', '标题', '简介', ?, '[]', 'SUBMITTED', ?, ?, ?, ?)
                """, draftId, MySqlFixtures.WORK_W, MySqlFixtures.CATEGORY_LITERATURE,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
        String id = newId();
        ContentVersionRow row = new ContentVersionRow(id, MySqlFixtures.WORK_W, null,
                "WORK", 1L, draftId, "标题", "简介", MySqlFixtures.CATEGORY_LITERATURE,
                "[\"奇幻\"]", null, null, null, "{}", "a".repeat(64), null,
                "SUBMITTED", null, 1L, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM, 1L);
        assertEquals(1, contentVersionMapper.insert(row));
        // 状态机字段更新：SUBMITTED → APPROVED + grade（04 §4：仅此列允许更新）
        assertEquals(1, contentVersionMapper.transitionState(id, "APPROVED", "G",
                MySqlFixtures.SYSTEM, MySqlFixtures.BUSINESS_TIME.plusSeconds(1)));
        ContentVersionRow approved = contentVersionMapper.findById(id);
        assertEquals("APPROVED", approved.state());
        assertEquals("G", approved.grade());
        assertEquals("标题", approved.title());
        assertEquals("{}", approved.materialSnapshot());
    }

    @Test
    void workflowAndPointerSwap() {
        String draftId = newId();
        jdbc.update("""
                INSERT IGNORE INTO draft (id, work_id, target_type, title, synopsis,
                    category_id, tags, state, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'WORK', '标题', '简介', ?, '[]', 'SUBMITTED', ?, ?, ?, ?)
                """, draftId, MySqlFixtures.WORK_W, MySqlFixtures.CATEGORY_LITERATURE,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
        String versionId = newId();
        jdbc.update("""
                INSERT IGNORE INTO content_version (id, work_id, target_type, revision_no,
                    source_draft_id, title, tags, material_snapshot, material_sha256,
                    state, policy_version, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'WORK', 2, ?, '标题', '[]', '{}', ?, 'APPROVED', 1, ?, ?, ?, ?)
                """, versionId, MySqlFixtures.WORK_W, draftId, "a".repeat(64),
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
        String workflowId = newId();
        PublicationWorkflowRow workflow = new PublicationWorkflowRow(workflowId, versionId,
                "WORK", MySqlFixtures.WORK_W, "SUSPENDED", 1L, null, "MACHINE_TIMEOUT",
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM, 1L);
        assertEquals(1, workflowMapper.insert(workflow));
        assertEquals("MACHINE_TIMEOUT", workflowMapper.findById(workflowId).suspendedReason());
        // 恢复进入 APPROVED：原因必须一并清空（CHECK 强制）
        assertEquals(1, workflowMapper.updateState(workflowId, "APPROVED", null,
                MySqlFixtures.SYSTEM, MySqlFixtures.BUSINESS_TIME.plusSeconds(1)));
        assertNull(workflowMapper.findById(workflowId).suspendedReason());
        // 指针插入 + 乐观锁原子交换（INV-02）
        String pointerId = newId();
        PublicationPointerRow pointer = new PublicationPointerRow(pointerId, "WORK",
                MySqlFixtures.WORK_W, versionId, MySqlFixtures.BUSINESS_TIME, 1L,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM, 1L);
        assertEquals(1, pointerMapper.insert(pointer));
        // 期望版本不匹配 → 行数 0（乐观锁兜底并发）
        assertEquals(0, pointerMapper.swapVersion(pointerId, versionId,
                MySqlFixtures.BUSINESS_TIME.plusSeconds(1), 2L, MySqlFixtures.SYSTEM,
                MySqlFixtures.BUSINESS_TIME.plusSeconds(1), 99L));
        assertEquals(1, pointerMapper.swapVersion(pointerId, versionId,
                MySqlFixtures.BUSINESS_TIME.plusSeconds(1), 2L, MySqlFixtures.SYSTEM,
                MySqlFixtures.BUSINESS_TIME.plusSeconds(1), 1L));
        PublicationPointerRow swapped = pointerMapper.findByTarget("WORK", MySqlFixtures.WORK_W);
        assertEquals(2L, swapped.publicationRevision());
    }

    @Test
    void idempotencyRoundTrip() {
        String subject = MySqlFixtures.AUTHOR_A;
        String key = UUID.randomUUID().toString();
        IdempotencyRow row = new IdempotencyRow(subject, "POST", "/api/v1/drafts", key,
                "c".repeat(64), 200, "{\"ok\":true}", MySqlFixtures.BUSINESS_TIME);
        assertEquals(1, idempotencyMapper.insert(row));
        IdempotencyRow loaded = idempotencyMapper.findByKey(subject, "POST",
                "/api/v1/drafts", key);
        assertNotNull(loaded);
        assertEquals("c".repeat(64), loaded.requestDigest());
        assertEquals(200, loaded.responseCode());
        // 不同 key 不冲突；T09：同键不同摘要 → B2003 由服务层比对 digest 差异判定
        assertNull(idempotencyMapper.findByKey(subject, "POST", "/api/v1/drafts",
                UUID.randomUUID().toString()));
        assertTrue(LocalDateTime.class.isAssignableFrom(loaded.expiresAt().getClass()));
    }
}
