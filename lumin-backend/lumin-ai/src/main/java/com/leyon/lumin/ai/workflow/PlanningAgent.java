package com.leyon.lumin.ai.workflow;

import com.leyon.lumin.ai.domain.AiStageType;
import com.leyon.lumin.ai.provider.AiProvider;
import com.leyon.lumin.ai.provider.AiRequest;
import org.springframework.stereotype.Component;

/** 章节计划 Agent：把作者指令与长期记忆压成可执行的本章结构。 */
@Component
public class PlanningAgent implements AiAgent {

    @Override
    public AiStageType stageType() {
        return AiStageType.PLAN;
    }

    @Override
    public String execute(AiAgentContext context, AiProvider provider) {
        return provider.generate(new AiRequest(
                PromptLibrary.planSystem(),
                PromptLibrary.planUser(context),
                0.6,
                800
        )).content().trim();
    }
}
