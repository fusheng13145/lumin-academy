package com.leyon.lumin.ai.workflow;

import com.leyon.lumin.ai.domain.AiStageType;
import com.leyon.lumin.ai.provider.AiProvider;

/** 单个 Agent：只负责一个阶段，输入输出通过上下文传递。 */
public interface AiAgent {

    AiStageType stageType();

    String execute(AiAgentContext context, AiProvider provider);
}
