package com.leyon.lumin.ai.workflow;

import com.leyon.lumin.ai.domain.MemoryFact;

import java.util.List;

/** 提示词集中管理；模型只负责生成，不在 Prompt 中拼接任何数据库 ID 或密钥。 */
public final class PromptLibrary {

    private PromptLibrary() {
    }

    public static String memoryContext(List<MemoryFact> facts, int limit) {
        if (facts == null || facts.isEmpty()) {
            return "（暂无已确认的长期记忆。请只依据本章指令创作，不要擅自新增与已有设定冲突的世界规则。）";
        }
        StringBuilder builder = new StringBuilder("以下是本书已确认的长期记忆，生成时必须保持一致：\n");
        facts.stream().limit(Math.max(1, limit)).forEach(fact ->
                builder.append("- [").append(fact.kind()).append("] ")
                        .append(fact.subjectKey()).append("：")
                        .append(fact.content()).append('\n'));
        return builder.toString();
    }

    public static String planSystem() {
        return "你是启明书院的资深网文策划编辑。你只输出中文，不寒暄、不解释写作过程。";
    }

    public static String planUser(AiAgentContext context) {
        return "作品 ID：" + context.workId() + "\n"
                + "章节 ID：" + (context.chapterId() == null ? "未指定" : context.chapterId()) + "\n"
                + context.memoryContext() + "\n"
                + "作者本章要求：" + context.safeInstruction() + "\n\n"
                + "请给出本章计划，必须包含：1) 开场钩子；2) 核心冲突；3) 人物动作与关系变化；"
                + "4) 需要揭示或埋下的信息；5) 结尾钩子。不超过 500 字。";
    }

    public static String draftSystem() {
        return "你是启明书院的签约网文作者，擅长在严格遵循既有设定前提下写出有画面感、有钩子的中文正文。"
                + "只输出正文，不输出标题、大纲、解释或 Markdown 标记。";
    }

    public static String draftUser(AiAgentContext context) {
        StringBuilder builder = new StringBuilder();
        builder.append(context.memoryContext()).append('\n')
                .append("本章计划：\n").append(context.plan()).append("\n\n")
                .append("作者要求：").append(context.safeInstruction()).append("\n\n");
        if (context.revisionNotes() != null && !context.revisionNotes().isBlank()) {
            builder.append("上一轮一致性问题，必须修正后再输出：\n")
                    .append(context.revisionNotes()).append("\n\n");
        }
        builder.append("请写本章正文，约 1200-2000 个中文字符。要求：\n")
                .append("- 第一段直接进入场景，不要总结前情；\n")
                .append("- 人物言行符合长期记忆中的人设，不新增未授权的关键设定；\n")
                .append("- 至少推进一个实质冲突，结尾留下下一章的钩子；\n")
                .append("- 不使用“总之”“综上所述”等总结腔。");
        return builder.toString();
    }

    public static String consistencySystem() {
        return "你是网文一致性审查员。你只做审查，不改写正文。输出必须简洁、可执行。";
    }

    public static String consistencyUser(AiAgentContext context) {
        return context.memoryContext() + "\n"
                + "本章计划：\n" + context.plan() + "\n\n"
                + "待审查正文：\n" + context.draft() + "\n\n"
                + "请检查：人物性格/能力是否突变；世界观规则是否被违反；时间线是否矛盾；"
                + "伏笔是否被错误回收；是否出现与长期记忆直接冲突的表述。\n"
                + "输出格式：每条一行，以 [BLOCKER] 或 [WARN] 开头；没有问题时只输出 OK。";
    }

    public static String memorySystem() {
        return "你是小说长期记忆抽取器。只输出 JSON，不输出解释、Markdown 或多余文字。";
    }

    public static String memoryUser(AiAgentContext context) {
        return context.memoryContext() + "\n"
                + "新章节正文：\n" + context.draft() + "\n\n"
                + "请抽取本章新增或发生变化、且后续章节必须记住的事实。只输出 JSON 数组，每项字段："
                + "kind（WORLD/CHARACTER/PLOT/FORESHADOW/STYLE/CONSTRAINT）、"
                + "subjectKey（稳定短名，如人物名或“北境灵脉”）、"
                + "content（一句完整中文事实）、confidence（0-1 数字）。"
                + "没有新增事实时输出 []。不要重复已有记忆。";
    }
}
