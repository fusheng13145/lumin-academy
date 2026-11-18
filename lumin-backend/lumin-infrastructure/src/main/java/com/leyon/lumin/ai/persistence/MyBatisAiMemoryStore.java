package com.leyon.lumin.ai.persistence;

import com.leyon.lumin.ai.domain.MemoryFact;
import com.leyon.lumin.ai.domain.MemoryKind;
import com.leyon.lumin.ai.domain.MemoryStatus;
import com.leyon.lumin.ai.port.AiMemoryStore;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Component
public class MyBatisAiMemoryStore implements AiMemoryStore {

    private final MemoryFactMapper memoryMapper;

    public MyBatisAiMemoryStore(MemoryFactMapper memoryMapper) {
        this.memoryMapper = memoryMapper;
    }

    @Override
    public List<MemoryFact> findActive(String workId) {
        return memoryMapper.findActive(workId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<MemoryFact> findByWorkId(String workId) {
        return memoryMapper.findByWorkId(workId).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void saveCandidates(String workId, List<MemoryFact> facts, String actorId) {
        for (MemoryFact fact : facts) {
            memoryMapper.insertFact(toRow(fact));
            memoryMapper.insertEvent(new MemoryEventRow(
                    UUID.randomUUID().toString(), workId, fact.id(), "EXTRACTED",
                    null, fact.content(), actorId, toLocal(fact.createdAt())
            ));
        }
    }

    @Override
    @Transactional
    public void confirm(String workId, String factId, String actorId) {
        MemoryFactRow row = memoryMapper.findById(factId);
        if (row == null || !row.workId().equals(workId)) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (!MemoryStatus.CANDIDATE.name().equals(row.status())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        memoryMapper.updateStatus(factId, MemoryStatus.ACTIVE.name(), now);
        memoryMapper.insertEvent(new MemoryEventRow(
                UUID.randomUUID().toString(), workId, factId, "CONFIRMED",
                "{\"status\":\"CANDIDATE\"}", "{\"status\":\"ACTIVE\"}", actorId, now
        ));
    }

    private MemoryFact toDomain(MemoryFactRow row) {
        return new MemoryFact(
                row.id(), row.workId(), MemoryKind.valueOf(row.kind()), row.subjectKey(),
                row.content(), MemoryStatus.valueOf(row.status()), row.confidence(),
                row.sourceVersion(), fromLocal(row.createdAt()), fromLocal(row.updatedAt())
        );
    }

    private MemoryFactRow toRow(MemoryFact fact) {
        return new MemoryFactRow(
                fact.id(), fact.workId(), fact.kind().name(), fact.subjectKey(), fact.content(),
                fact.status().name(), fact.confidence(), fact.sourceVersion(),
                toLocal(fact.createdAt()), toLocal(fact.updatedAt())
        );
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromLocal(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
