package com.leyon.lumin.ai.workflow;

import com.leyon.lumin.ai.domain.AiStageType;
import com.leyon.lumin.ai.provider.AiProvider;
import com.leyon.lumin.ai.provider.AiRequest;
import org.springframework.stereotype.Component;

/** 一致性 Agent：只标问题，不重写；BLOCKER 才触发有限修订。 */
@Component
public class ConsistencyAgent implements AiAgent {

    @Override
    public AiStageType stageType() {
        return AiStageType.CONSISTENCY;
    }

    @Override
    public String execute(AiAgentContext context, AiProvider provider) {
        return provider.generate(new AiRequest(
                PromptLibrary.consistencySystem(),
                PromptLibrary.consistencyUser(context),
                0.2,
                1200
        )).content().trim();
    }
}
