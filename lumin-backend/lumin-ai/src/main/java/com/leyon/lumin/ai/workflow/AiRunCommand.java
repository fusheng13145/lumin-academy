package com.leyon.lumin.ai.workflow;

import com.leyon.lumin.ai.domain.AiMode;

public record AiRunCommand(String workId, String chapterId, AiMode mode, String instruction, String authorId) {
}
