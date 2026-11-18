package com.leyon.lumin.ai.workflow;

import com.leyon.lumin.ai.config.AiProperties;
import com.leyon.lumin.ai.domain.AiStageType;
import com.leyon.lumin.ai.provider.AiProvider;
import com.leyon.lumin.ai.provider.AiRequest;
import org.springframework.stereotype.Component;

/** 起草 Agent：按计划与记忆生成正文；修订时复用同一 Agent，attempt 递增。 */
@Component
public class DraftingAgent implements AiAgent {

    private final AiProperties properties;

    public DraftingAgent(AiProperties properties) {
        this.properties = properties;
    }

    @Override
    public AiStageType stageType() {
        return AiStageType.DRAFT;
    }

    @Override
    public String execute(AiAgentContext context, AiProvider provider) {
        int maxTokens = Math.max(800, properties.getOllama().getMaxTokens());
        return provider.generate(new AiRequest(
                PromptLibrary.draftSystem(),
                PromptLibrary.draftUser(context),
                0.75,
                maxTokens
        )).content().trim();
    }
}
