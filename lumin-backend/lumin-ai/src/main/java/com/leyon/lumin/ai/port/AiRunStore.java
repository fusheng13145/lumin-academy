package com.leyon.lumin.ai.port;

import com.leyon.lumin.ai.domain.AiCandidate;
import com.leyon.lumin.ai.domain.AiRun;
import com.leyon.lumin.ai.domain.AiStage;

import java.util.List;
import java.util.Optional;

/** 运行记录出站端口；由 infrastructure 用 MyBatis 实现，AI 模块不直接碰数据库。 */
public interface AiRunStore {

    void insertRun(AiRun run);

    void updateRun(AiRun run);

    Optional<AiRun> findRun(String runId);

    void insertStage(AiStage stage);

    void updateStage(AiStage stage);

    List<AiStage> findStages(String runId);

    void insertCandidate(AiCandidate candidate);

    void updateCandidate(AiCandidate candidate);

    List<AiCandidate> findCandidates(String runId);
}
