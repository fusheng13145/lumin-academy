package com.leyon.lumin.ai.workflow;

import com.leyon.lumin.ai.api.AiRunView;
import com.leyon.lumin.ai.config.AiProperties;
import com.leyon.lumin.ai.domain.AiCandidate;
import com.leyon.lumin.ai.domain.AiCandidateState;
import com.leyon.lumin.ai.domain.AiMode;
import com.leyon.lumin.ai.domain.AiRun;
import com.leyon.lumin.ai.domain.AiRunState;
import com.leyon.lumin.ai.domain.AiStage;
import com.leyon.lumin.ai.domain.MemoryFact;
import com.leyon.lumin.ai.port.AiMemoryStore;
import com.leyon.lumin.ai.port.AiRunStore;
import com.leyon.lumin.ai.provider.AiCompletion;
import com.leyon.lumin.ai.provider.AiProvider;
import com.leyon.lumin.ai.provider.AiProviderRouter;
import com.leyon.lumin.ai.provider.AiRequest;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 纯 AI workflow 的同步编排测试：用内存端口与假供应商验证阶段、候选与记忆抽取。 */
class AiWorkflowServiceTest {

    @Test
    void autonomousRunProducesPlanDraftConsistencyCandidateAndMemory() {
        AiRunStore runStore = new InMemoryRunStore();
        AiMemoryStore memoryStore = new InMemoryMemoryStore();
        AiProperties properties = new AiProperties();
        properties.setProvider("fake");
        FakeProvider provider = new FakeProvider();
        AiProviderRouter router = new AiProviderRouter(properties, List.of(provider));
        AiWorkflowService service = new AiWorkflowService(
                runStore, memoryStore, router, properties,
                new PlanningAgent(), new DraftingAgent(properties),
                new ConsistencyAgent(), new MemoryExtractionAgent(),
                new AiMemoryParser(new ObjectMapper()),
                Runnable::run
        );

        AiRunView accepted = service.start(new AiRunCommand(
                "work-1", "chapter-1", AiMode.AUTONOMOUS,
                "让主角在北境灵脉前做出第一次真正选择", "author-1"
        ));
        AiRunView result = service.get(accepted.id());

        assertEquals("WAITING_AUTHOR", result.state());
        assertEquals(1, result.candidates().size());
        assertTrue(result.candidates().get(0).content().contains("北境"));
        assertTrue(result.stages().stream().anyMatch(stage -> stage.stageType().equals("PLAN")));
        assertTrue(result.stages().stream().anyMatch(stage -> stage.stageType().equals("DRAFT")));
        assertTrue(result.stages().stream().anyMatch(stage -> stage.stageType().equals("CONSISTENCY")));
        assertTrue(result.stages().stream().anyMatch(stage -> stage.stageType().equals("MEMORY_EXTRACT")));
        assertEquals(1, memoryStore.findByWorkId("work-1").size());
    }

    @Test
    void blockerTriggersOneRevisionAttempt() {
        AiRunStore runStore = new InMemoryRunStore();
        AiMemoryStore memoryStore = new InMemoryMemoryStore();
        AiProperties properties = new AiProperties();
        properties.setProvider("fake");
        properties.getWorkflow().setMaxRevisionAttempts(1);
        FakeProvider provider = new FakeProvider();
        provider.blockOnce = true;
        provider.firstConsistency = true;
        AiWorkflowService service = new AiWorkflowService(
                runStore, memoryStore, new AiProviderRouter(properties, List.of(provider)), properties,
                new PlanningAgent(), new DraftingAgent(properties),
                new ConsistencyAgent(), new MemoryExtractionAgent(),
                new AiMemoryParser(new ObjectMapper()),
                Runnable::run
        );

        AiRunView accepted = service.start(new AiRunCommand(
                "work-2", "chapter-2", AiMode.ASSISTED, "继续上一章", "author-1"
        ));
        AiRunView result = service.get(accepted.id());

        assertEquals("WAITING_AUTHOR", result.state());
        assertTrue(result.stages().stream().anyMatch(stage -> stage.stageType().equals("REVISION")));
        assertEquals(2, result.stages().stream().filter(stage -> stage.stageType().equals("CONSISTENCY")).count());
    }

    @Test
    void candidateCanBeAcceptedOnlyOnce() {
        AiRunStore runStore = new InMemoryRunStore();
        AiMemoryStore memoryStore = new InMemoryMemoryStore();
        AiProperties properties = new AiProperties();
        properties.setProvider("fake");
        AiWorkflowService service = new AiWorkflowService(
                runStore, memoryStore, new AiProviderRouter(properties, List.of(new FakeProvider())), properties,
                new PlanningAgent(), new DraftingAgent(properties),
                new ConsistencyAgent(), new MemoryExtractionAgent(),
                new AiMemoryParser(new ObjectMapper()),
                Runnable::run
        );

        AiRunView result = service.get(service.start(new AiRunCommand(
                "work-3", "chapter-3", AiMode.ASSISTED, "写一段收尾", "author-1"
        )).id());
        String candidateId = result.candidates().get(0).id();

        AiRunView accepted = service.decideCandidate(result.id(), candidateId, AiCandidateState.ACCEPTED);
        assertEquals("ACCEPTED", accepted.candidates().get(0).state());
        assertThrows(RuntimeException.class,
                () -> service.decideCandidate(result.id(), candidateId, AiCandidateState.REJECTED));
    }

    private static class FakeProvider implements AiProvider {
        private boolean blockOnce;
        private boolean firstConsistency;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake-model";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public AiCompletion generate(AiRequest request) {
            String prompt = request.userPrompt();
            if (prompt.contains("请给出本章计划")) {
                return new AiCompletion("计划：北境灵脉前，主角必须在亲情与道途之间选择。", name(), model(), 10, 20, "stop");
            }
            if (prompt.contains("请写本章正文")) {
                if (blockOnce) {
                    blockOnce = false;
                    return new AiCompletion("北境灵脉前，主角的旧伤突然复发，与设定冲突。", name(), model(), 10, 20, "stop");
                }
                return new AiCompletion("北境灵脉在雪夜中亮起，主角握紧断剑，第一次选择不再回头。", name(), model(), 10, 20, "stop");
            }
            if (prompt.contains("请检查")) {
                if (firstConsistency) {
                    firstConsistency = false;
                    return new AiCompletion("[BLOCKER] 旧伤复发与长期记忆冲突", name(), model(), 10, 10, "stop");
                }
                return new AiCompletion("OK", name(), model(), 10, 10, "stop");
            }
            if (prompt.contains("请抽取")) {
                return new AiCompletion("[{\"kind\":\"CHARACTER\",\"subjectKey\":\"主角\",\"content\":\"主角在北境灵脉前做出选择\",\"confidence\":0.9}]", name(), model(), 10, 10, "stop");
            }
            return new AiCompletion("输出", name(), model(), 10, 10, "stop");
        }
    }

    private static class InMemoryRunStore implements AiRunStore {
        private final Map<String, AiRun> runs = new HashMap<>();
        private final List<AiStage> stages = new ArrayList<>();
        private final List<AiCandidate> candidates = new ArrayList<>();

        @Override
        public void insertRun(AiRun run) {
            runs.put(run.id(), run);
        }

        @Override
        public void updateRun(AiRun run) {
            runs.put(run.id(), run);
        }

        @Override
        public Optional<AiRun> findRun(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public void insertStage(AiStage stage) {
            stages.add(stage);
        }

        @Override
        public void updateStage(AiStage stage) {
            for (int i = 0; i < stages.size(); i++) {
                if (stages.get(i).id().equals(stage.id())) {
                    stages.set(i, stage);
                    return;
                }
            }
        }

        @Override
        public List<AiStage> findStages(String runId) {
            return stages.stream().filter(stage -> stage.runId().equals(runId)).toList();
        }

        @Override
        public void insertCandidate(AiCandidate candidate) {
            candidates.add(candidate);
        }

        @Override
        public void updateCandidate(AiCandidate candidate) {
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).id().equals(candidate.id())) {
                    candidates.set(i, candidate);
                    return;
                }
            }
        }

        @Override
        public List<AiCandidate> findCandidates(String runId) {
            return candidates.stream().filter(candidate -> candidate.runId().equals(runId)).toList();
        }
    }

    private static class InMemoryMemoryStore implements AiMemoryStore {
        private final List<MemoryFact> facts = new ArrayList<>();

        @Override
        public List<MemoryFact> findActive(String workId) {
            return facts.stream()
                    .filter(fact -> fact.workId().equals(workId))
                    .filter(fact -> fact.status() == com.leyon.lumin.ai.domain.MemoryStatus.ACTIVE)
                    .toList();
        }

        @Override
        public List<MemoryFact> findByWorkId(String workId) {
            return facts.stream().filter(fact -> fact.workId().equals(workId)).toList();
        }

        @Override
        public void saveCandidates(String workId, List<MemoryFact> newFacts, String actorId) {
            facts.addAll(newFacts);
        }

        @Override
        public void confirm(String workId, String factId, String actorId) {
            for (int i = 0; i < facts.size(); i++) {
                MemoryFact fact = facts.get(i);
                if (fact.id().equals(factId)) {
                    facts.set(i, new MemoryFact(
                            fact.id(), fact.workId(), fact.kind(), fact.subjectKey(), fact.content(),
                            com.leyon.lumin.ai.domain.MemoryStatus.ACTIVE, fact.confidence(),
                            fact.sourceVersion(), fact.createdAt(), Instant.now()
                    ));
                }
            }
        }
    }
}
