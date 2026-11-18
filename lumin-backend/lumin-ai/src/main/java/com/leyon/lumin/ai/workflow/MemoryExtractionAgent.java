package com.leyon.lumin.ai.workflow;

import com.leyon.lumin.ai.domain.AiStageType;
import com.leyon.lumin.ai.provider.AiProvider;
import com.leyon.lumin.ai.provider.AiRequest;
import org.springframework.stereotype.Component;

/** 记忆抽取 Agent：只产出候选 JSON，不直接写库；确认权在作者。 */
@Component
public class MemoryExtractionAgent implements AiAgent {

    @Override
    public AiStageType stageType() {
        return AiStageType.MEMORY_EXTRACT;
    }

    @Override
    public String execute(AiAgentContext context, AiProvider provider) {
        return provider.generate(new AiRequest(
                PromptLibrary.memorySystem(),
                PromptLibrary.memoryUser(context),
                0.15,
                1600
        )).content().trim();
    }
}
