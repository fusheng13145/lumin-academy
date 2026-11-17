package com.leyon.lumin.foundation.audit;

/**
 * 追加式审计端口（02 §3.1：策略与审计使用 foundation 声明的端口；
 * audit 模块提供适配器实现，各业务模块仅依赖本端口——跨模块禁止直接
 * Repository 访问）。INV-06：审计与业务状态同事务写入由调用方事务承载；
 * Tier0 审计写失败时高危事务回滚（03 §6）。
 */
public interface AuditRecorder {

    /**
     * 追加一条审计记录（append-only，不记正文/令牌/证件，04 §5.1）。
     */
    void record(AuditEntry entry);

    /**
     * 追加带状态迁移的审计记录（T35：高危变更留 before/after 脱敏快照——
     * 调用方仅传业务态类字段，不传正文/令牌/证件；digest 覆盖前后状态）。
     */
    void recordTransition(AuditEntry entry, java.util.Map<String, String> before,
                          java.util.Map<String, String> after);

    /** 审计条目（脱敏由调用方负责；digest 由适配器对规范字段计算）。 */
    record AuditEntry(String actorId, String roleContext, String action,
                      String resourceType, String resourceId, String reason,
                      String traceId, java.time.Instant eventAt) {
    }
}
