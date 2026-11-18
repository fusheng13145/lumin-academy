package com.leyon.lumin.ai.workflow;

import java.util.Objects;

/** Agent 之间传递的只读上下文；每一步产生新快照，避免隐式共享可变状态。 */
public record AiAgentContext(
        String workId,
        String chapterId,
        String instruction,
        String memoryContext,
        String plan,
        String draft,
        String consistencyNotes,
        String revisionNotes,
        int attempt
) {

    public static AiAgentContext base(String workId, String chapterId, String instruction, String memoryContext) {
        return new AiAgentContext(workId, chapterId, instruction, memoryContext, "", "", "", "", 1);
    }

    public AiAgentContext withPlan(String value) {
        return new AiAgentContext(workId, chapterId, instruction, memoryContext, value, draft, consistencyNotes, revisionNotes, attempt);
    }

    public AiAgentContext withDraft(String value) {
        return new AiAgentContext(workId, chapterId, instruction, memoryContext, plan, value, consistencyNotes, revisionNotes, attempt);
    }

    public AiAgentContext withConsistency(String value) {
        return new AiAgentContext(workId, chapterId, instruction, memoryContext, plan, draft, value, revisionNotes, attempt);
    }

    public AiAgentContext withRevision(String revisionNotes, int nextAttempt) {
        return new AiAgentContext(workId, chapterId, instruction, memoryContext, plan, draft, consistencyNotes, revisionNotes, nextAttempt);
    }

    public String safeInstruction() {
        return Objects.toString(instruction, "");
    }
}
