package com.leyon.lumin.ai.persistence;

import com.leyon.lumin.ai.domain.AiCandidate;
import com.leyon.lumin.ai.domain.AiCandidateState;
import com.leyon.lumin.ai.domain.AiMode;
import com.leyon.lumin.ai.domain.AiRun;
import com.leyon.lumin.ai.domain.AiRunState;
import com.leyon.lumin.ai.domain.AiStage;
import com.leyon.lumin.ai.domain.AiStageState;
import com.leyon.lumin.ai.domain.AiStageType;
import com.leyon.lumin.ai.port.AiRunStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Component
public class MyBatisAiRunStore implements AiRunStore {

    private final AiRunMapper runMapper;
    private final AiStageMapper stageMapper;
    private final AiCandidateMapper candidateMapper;

    public MyBatisAiRunStore(AiRunMapper runMapper, AiStageMapper stageMapper, AiCandidateMapper candidateMapper) {
        this.runMapper = runMapper;
        this.stageMapper = stageMapper;
        this.candidateMapper = candidateMapper;
    }

    @Override
    public void insertRun(AiRun run) {
        runMapper.insert(toRow(run));
    }

    @Override
    public void updateRun(AiRun run) {
        runMapper.update(toRow(run));
    }

    @Override
    public Optional<AiRun> findRun(String runId) {
        return Optional.ofNullable(runMapper.findById(runId)).map(this::toDomain);
    }

    @Override
    public void insertStage(AiStage stage) {
        stageMapper.insert(toRow(stage));
    }

    @Override
    public void updateStage(AiStage stage) {
        stageMapper.update(toRow(stage));
    }

    @Override
    public List<AiStage> findStages(String runId) {
        return stageMapper.findByRunId(runId).stream().map(this::toDomain).toList();
    }

    @Override
    public void insertCandidate(AiCandidate candidate) {
        candidateMapper.insert(new AiCandidateRow(
                candidate.id(), candidate.runId(), candidate.content(), candidate.source(),
                candidate.state().name(), toLocal(candidate.createdAt())
        ));
    }

    @Override
    public void updateCandidate(AiCandidate candidate) {
        candidateMapper.updateState(candidate.id(), candidate.runId(), candidate.state().name());
    }

    @Override
    public List<AiCandidate> findCandidates(String runId) {
        return candidateMapper.findByRunId(runId).stream()
                .map(row -> new AiCandidate(
                        row.id(), row.runId(), row.content(), row.source(),
                        AiCandidateState.valueOf(row.state()), fromLocal(row.createdAt())
                ))
                .toList();
    }

    private AiRunRow toRow(AiRun run) {
        return new AiRunRow(
                run.id(), run.workId(), run.chapterId(), run.mode().name(), run.state().name(),
                run.instruction(), run.provider(), run.model(), run.memoryRevision(),
                run.errorMessage(), toLocal(run.createdAt()), toLocal(run.updatedAt())
        );
    }

    private AiRun toDomain(AiRunRow row) {
        return new AiRun(
                row.id(), row.workId(), row.chapterId(), AiMode.valueOf(row.mode()),
                AiRunState.valueOf(row.state()), row.instruction(), row.provider(), row.model(),
                row.memoryRevision(), row.errorMessage(), fromLocal(row.createdAt()), fromLocal(row.updatedAt())
        );
    }

    private AiStageRow toRow(AiStage stage) {
        return new AiStageRow(
                stage.id(), stage.runId(), stage.stageType().name(), stage.attempt(), stage.state().name(),
                stage.output(), stage.errorMessage(), toLocal(stage.startedAt()), toLocal(stage.finishedAt())
        );
    }

    private AiStage toDomain(AiStageRow row) {
        return new AiStage(
                row.id(), row.runId(), AiStageType.valueOf(row.stageType()), row.attempt(),
                AiStageState.valueOf(row.state()), row.output(), row.errorMessage(),
                fromLocal(row.startedAt()), fromLocal(row.finishedAt())
        );
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromLocal(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
