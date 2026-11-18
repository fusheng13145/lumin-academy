package com.leyon.lumin.ai.port;

import com.leyon.lumin.ai.domain.MemoryFact;

import java.util.List;

/** 长期记忆出站端口；AI 抽取结果先写候选，作者确认后由应用层提升为 ACTIVE。 */
public interface AiMemoryStore {

    List<MemoryFact> findActive(String workId);

    List<MemoryFact> findByWorkId(String workId);

    void saveCandidates(String workId, List<MemoryFact> facts, String actorId);

    void confirm(String workId, String factId, String actorId);
}
