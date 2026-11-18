package com.leyon.lumin.ai.workflow;

import com.leyon.lumin.ai.api.MemoryFactView;
import com.leyon.lumin.ai.domain.MemoryFact;
import com.leyon.lumin.ai.port.AiMemoryStore;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import org.springframework.stereotype.Service;

import java.util.List;

/** 长期记忆查询与作者确认；AI 抽取不自动提升为 ACTIVE。 */
@Service
public class AiMemoryService {

    private final AiMemoryStore memoryStore;

    public AiMemoryService(AiMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    public List<MemoryFactView> list(String workId) {
        if (workId == null || workId.isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return memoryStore.findByWorkId(workId).stream().map(this::toView).toList();
    }

    public MemoryFactView confirm(String workId, String factId, String actorId) {
        memoryStore.confirm(workId, factId, actorId);
        return memoryStore.findByWorkId(workId).stream()
                .filter(fact -> fact.id().equals(factId))
                .findFirst()
                .map(this::toView)
                .orElseThrow(() -> new LuminBusinessException(ErrorCode.B2004));
    }

    private MemoryFactView toView(MemoryFact fact) {
        return new MemoryFactView(
                fact.id(), fact.kind().name(), fact.subjectKey(), fact.content(),
                fact.status().name(), fact.confidence(), fact.sourceVersion(),
                fact.createdAt(), fact.updatedAt()
        );
    }
}
