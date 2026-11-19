package com.leyon.lumin.foundation.port;

import java.util.Set;

/**
 * 审核队列作用域端口（05 §3.3 listCases：按有效角色与 queue scope 过滤）。
 * moderation 仅依赖本端口；role_assignment 的读取权威在 identity 域。
 */
public interface ReviewerQueueScopePort {

    /** global=true 表示可读全部队列；否则仅 queueIds 内的队列可读。 */
    record QueueScope(boolean global, Set<String> queueIds) {
        public QueueScope {
            queueIds = queueIds == null ? Set.of() : Set.copyOf(queueIds);
        }

        public static QueueScope all() {
            return new QueueScope(true, Set.of());
        }

        public static QueueScope none() {
            return new QueueScope(false, Set.of());
        }
    }

    /** 解析主体当前角色对应的有效队列作用域；无有效绑定按空作用域失败关闭。 */
    QueueScope resolve(String subjectId, String role);
}
