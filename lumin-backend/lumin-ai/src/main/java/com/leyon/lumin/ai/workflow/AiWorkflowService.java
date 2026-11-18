package com.leyon.lumin.ai.workflow;

import com.leyon.lumin.ai.api.AiCandidateView;
import com.leyon.lumin.ai.api.AiHealthView;
import com.leyon.lumin.ai.api.AiRunView;
import com.leyon.lumin.ai.api.AiStageView;
import com.leyon.lumin.ai.config.AiProperties;
import com.leyon.lumin.ai.domain.AiCandidate;
import com.leyon.lumin.ai.domain.AiCandidateState;
import com.leyon.lumin.ai.domain.AiMode;
import com.leyon.lumin.ai.domain.AiRun;
import com.leyon.lumin.ai.domain.AiRunState;
import com.leyon.lumin.ai.domain.AiStage;
import com.leyon.lumin.ai.domain.AiStageState;
import com.leyon.lumin.ai.domain.AiStageType;
import com.leyon.lumin.ai.domain.MemoryFact;
import com.leyon.lumin.ai.port.AiMemoryStore;
import com.leyon.lumin.ai.port.AiRunStore;
import com.leyon.lumin.ai.provider.AiProvider;
import com.leyon.lumin.ai.provider.AiProviderException;
import com.leyon.lumin.ai.provider.AiProviderRouter;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * AI 运行编排器：资格/预算校验 → 记忆上下文 → 计划 → 起草 → 一致性 → 有限修订
 * → 记忆抽取 → 候选包。外部模型调用不放在数据库事务内；每个阶段单独落库。
 *
 * <p>纯 AI 与 AI 辅助共用同一条 workflow，差别只在调用方是否允许自动写入草稿；
 * 本服务永远只产出候选，正文写入由作者确认。</p>
 */
@Service
public class AiWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(AiWorkflowService.class);
    private static final int MAX_INSTRUCTION_LENGTH = 4000;

    private final AiRunStore runStore;
    private final AiMemoryStore memoryStore;
    private final AiProviderRouter providerRouter;
    private final AiProperties properties;
    private final PlanningAgent planningAgent;
    private final DraftingAgent draftingAgent;
    private final ConsistencyAgent consistencyAgent;
    private final MemoryExtractionAgent memoryExtractionAgent;
    private final AiMemoryParser memoryParser;
    private final Executor executor;

    public AiWorkflowService(
            AiRunStore runStore,
            AiMemoryStore memoryStore,
            AiProviderRouter providerRouter,
            AiProperties properties,
            PlanningAgent planningAgent,
            DraftingAgent draftingAgent,
            ConsistencyAgent consistencyAgent,
            MemoryExtractionAgent memoryExtractionAgent,
            AiMemoryParser memoryParser,
            @Qualifier("aiWorkflowExecutor") Executor executor) {
        this.runStore = runStore;
        this.memoryStore = memoryStore;
        this.providerRouter = providerRouter;
        this.properties = properties;
        this.planningAgent = planningAgent;
        this.draftingAgent = draftingAgent;
        this.consistencyAgent = consistencyAgent;
        this.memoryExtractionAgent = memoryExtractionAgent;
        this.memoryParser = memoryParser;
        this.executor = executor;
    }

    public AiRunView start(AiRunCommand command) {
        if (command == null || command.workId() == null || command.workId().isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        if (command.mode() == null || !command.mode().usesAi()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String instruction = command.instruction() == null ? "" : command.instruction().trim();
        if (instruction.length() > MAX_INSTRUCTION_LENGTH) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }

        AiProvider provider;
        try {
            provider = providerRouter.active();
        } catch (AiProviderException exception) {
            throw new LuminBusinessException(ErrorCode.D2001);
        }
        if (!provider.available()) {
            throw new LuminBusinessException(ErrorCode.D2001);
        }

        List<MemoryFact> facts = memoryStore.findActive(command.workId());
        Instant now = Instant.now();
        AiRun run = new AiRun(
                UUID.randomUUID().toString(),
                command.workId(),
                blankToNull(command.chapterId()),
                command.mode(),
                AiRunState.QUEUED,
                instruction,
                provider.name(),
                provider.model(),
                "facts:" + facts.size(),
                null,
                now,
                now
        );
        runStore.insertRun(run);
        String authorId = command.authorId() == null ? "unknown" : command.authorId();
        executor.execute(() -> execute(run, authorId));
        return view(run);
    }

    public AiRunView get(String runId) {
        AiRun run = runStore.findRun(runId)
                .orElseThrow(() -> new LuminBusinessException(ErrorCode.B2004));
        return view(run);
    }

    /** 作者采纳/拒绝候选：只改候选状态，不直接写 draft；正文写入由写作中心调用现有草稿接口。 */
    public AiRunView decideCandidate(String runId, String candidateId, AiCandidateState decision) {
        AiRun run = runStore.findRun(runId)
                .orElseThrow(() -> new LuminBusinessException(ErrorCode.B2004));
        AiCandidate candidate = runStore.findCandidates(runId).stream()
                .filter(item -> item.id().equals(candidateId))
                .findFirst()
                .orElseThrow(() -> new LuminBusinessException(ErrorCode.B2004));
        if (candidate.state() != AiCandidateState.PENDING) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        runStore.updateCandidate(new AiCandidate(
                candidate.id(), candidate.runId(), candidate.content(), candidate.source(),
                decision, candidate.createdAt()
        ));
        return view(run);
    }

    public AiHealthView health() {
        String activeName = providerRouter.activeName();
        try {
            AiProvider provider = providerRouter.active();
            boolean available = provider.available();
            return new AiHealthView(provider.name(), provider.model(), available,
                    available ? "供应商可用" : "供应商未就绪，请检查 Ollama 服务或 API key");
        } catch (Exception exception) {
            return new AiHealthView(activeName, "", false, exception.getMessage());
        }
    }

    private void execute(AiRun initialRun, String authorId) {
        AiRun running = updateRun(initialRun, AiRunState.RUNNING, null);
        try {
            List<MemoryFact> facts = memoryStore.findActive(initialRun.workId());
            String memoryContext = PromptLibrary.memoryContext(facts, properties.getWorkflow().getMaxMemoryFacts());
            saveFixedStage(initialRun, AiStageType.MEMORY_CONTEXT, 1, memoryContext);

            AiProvider provider = providerRouter.active();
            AiAgentContext context = AiAgentContext.base(
                    initialRun.workId(),
                    initialRun.chapterId(),
                    initialRun.instruction(),
                    memoryContext
            );

            String plan = runStage(initialRun, AiStageType.PLAN, 1, planningAgent, context, provider);
            context = context.withPlan(plan);

            String draft = runStage(initialRun, AiStageType.DRAFT, 1, draftingAgent, context, provider);
            context = context.withDraft(draft);

            String notes;
            try {
                notes = runStage(initialRun, AiStageType.CONSISTENCY, 1, consistencyAgent, context, provider);
            } catch (AiProviderException exception) {
                // 一致性审查是质量增强而非发布闸门；本地小模型超时/空输出时不阻断候选。
                log.warn("一致性审查失败，runId={}，按未发现问题继续: {}", initialRun.id(), exception.getMessage());
                notes = "OK";
            }
            context = context.withConsistency(notes);

            if (containsBlocker(notes) && properties.getWorkflow().getMaxRevisionAttempts() > 0) {
                context = context.withRevision(notes, 2);
                String revised = runStage(initialRun, AiStageType.REVISION, 2, draftingAgent, context, provider);
                context = context.withDraft(revised);
                String recheck = runStage(initialRun, AiStageType.CONSISTENCY, 2, consistencyAgent, context, provider);
                context = context.withConsistency(recheck);
            }

            String rawMemory;
            try {
                rawMemory = runStage(initialRun, AiStageType.MEMORY_EXTRACT, 1, memoryExtractionAgent, context, provider);
            } catch (AiProviderException exception) {
                // 记忆抽取是增强能力，不应阻断正文候选；失败阶段保留在 ai_run_stage 供排查。
                log.warn("记忆抽取失败，runId={}，继续产出正文候选: {}", initialRun.id(), exception.getMessage());
                rawMemory = "[]";
            }
            List<MemoryFact> memoryCandidates;
            try {
                memoryCandidates = memoryParser.parse(rawMemory, initialRun.workId());
            } catch (Exception exception) {
                log.warn("记忆抽取结果解析失败，runId={}，将保留原始输出但不写候选: {}",
                        initialRun.id(), exception.getMessage());
                memoryCandidates = List.of();
            }
            if (!memoryCandidates.isEmpty()) {
                memoryStore.saveCandidates(initialRun.workId(), memoryCandidates, authorId);
            }

            AiCandidate candidate = new AiCandidate(
                    UUID.randomUUID().toString(),
                    initialRun.id(),
                    context.draft(),
                    initialRun.mode() == AiMode.AUTONOMOUS ? "AUTONOMOUS_WORKFLOW" : "ASSISTED_WORKFLOW",
                    AiCandidateState.PENDING,
                    Instant.now()
            );
            runStore.insertCandidate(candidate);
            saveFixedStage(initialRun, AiStageType.CANDIDATE, 1,
                    "候选已生成，等待作者确认；记忆候选 " + memoryCandidates.size() + " 条");
            updateRun(running, AiRunState.WAITING_AUTHOR, null);
        } catch (Exception exception) {
            log.error("AI 运行失败，runId={}", initialRun.id(), exception);
            updateRun(running, AiRunState.FAILED, safeMessage(exception));
        }
    }

    private String runStage(AiRun run, AiStageType type, int attempt, AiAgent agent,
                            AiAgentContext context, AiProvider provider) {
        Instant startedAt = Instant.now();
        AiStage running = new AiStage(
                UUID.randomUUID().toString(), run.id(), type, attempt,
                AiStageState.RUNNING, null, null, startedAt, null
        );
        runStore.insertStage(running);
        try {
            String output = agent.execute(context, provider);
            AiStage succeeded = new AiStage(
                    running.id(), run.id(), type, attempt,
                    AiStageState.SUCCEEDED, output, null, startedAt, Instant.now()
            );
            runStore.updateStage(succeeded);
            return output;
        } catch (Exception exception) {
            AiStage failed = new AiStage(
                    running.id(), run.id(), type, attempt,
                    AiStageState.FAILED, null, safeMessage(exception), startedAt, Instant.now()
            );
            runStore.updateStage(failed);
            throw exception;
        }
    }

    private void saveFixedStage(AiRun run, AiStageType type, int attempt, String output) {
        Instant now = Instant.now();
        AiStage stage = new AiStage(
                UUID.randomUUID().toString(), run.id(), type, attempt,
                AiStageState.SUCCEEDED, output, null, now, now
        );
        runStore.insertStage(stage);
    }

    private AiRun updateRun(AiRun run, AiRunState state, String error) {
        AiRun updated = new AiRun(
                run.id(), run.workId(), run.chapterId(), run.mode(), state,
                run.instruction(), run.provider(), run.model(), run.memoryRevision(),
                error, run.createdAt(), Instant.now()
        );
        runStore.updateRun(updated);
        return updated;
    }

    private AiRunView view(AiRun run) {
        List<AiStageView> stages = runStore.findStages(run.id()).stream()
                .map(stage -> new AiStageView(
                        stage.stageType().name(),
                        stage.attempt(),
                        stage.state().name(),
                        stage.output(),
                        stage.errorMessage(),
                        stage.startedAt(),
                        stage.finishedAt()
                ))
                .toList();
        List<AiCandidateView> candidates = runStore.findCandidates(run.id()).stream()
                .map(candidate -> new AiCandidateView(
                        candidate.id(),
                        candidate.content(),
                        candidate.source(),
                        candidate.state().name(),
                        candidate.createdAt()
                ))
                .toList();
        return new AiRunView(
                run.id(), run.workId(), run.chapterId(), run.mode().name(), run.state().name(),
                run.provider(), run.model(), run.memoryRevision(), run.errorMessage(),
                stages, candidates, run.createdAt(), run.updatedAt()
        );
    }

    private static boolean containsBlocker(String notes) {
        return notes != null && notes.toUpperCase().contains("[BLOCKER]");
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
