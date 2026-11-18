import type { AiRun, AiStage, MemoryFact } from '../types';
import type { CreationMode } from '../../lib/creationMode';

const STAGE_SETS: Record<Exclude<CreationMode, 'manual'>, Array<{ id: string; label: string; detail: string }>> = {
  assisted: [
    { id: 'read-memory', label: '读取长期记忆', detail: '人物、世界观、前情' },
    { id: 'draft', label: '生成候选段落', detail: '按当前章节上下文续写' },
    { id: 'style', label: '风格校对', detail: '匹配本书语感与叙事视角' },
    { id: 'ready', label: '候选就绪', detail: '等待作者确认入稿' },
  ],
  autonomous: [
    { id: 'read-memory', label: '读取长期记忆', detail: '锁定人物卡、世界观与伏笔' },
    { id: 'plan', label: '情节规划 Agent', detail: '给出本章目标与冲突节拍' },
    { id: 'draft', label: '章节起草 Agent', detail: '按节拍产出场景草稿' },
    { id: 'consistency', label: '一致性审校 Agent', detail: '比对前文事实与人物口吻' },
    { id: 'memory', label: '记忆归档', detail: '将新事实写入长期记忆' },
    { id: 'ready', label: '候选章节就绪', detail: '等待作者审阅或退回' },
  ],
};

export function createAiRun(
  mode: Exclude<CreationMode, 'manual'>,
  prompt: string,
  memory: MemoryFact[]
): AiRun {
  const stages: AiStage[] = STAGE_SETS[mode].map((stage) => ({
    ...stage,
    status: 'pending',
  }));

  return {
    id: `run-${Date.now()}`,
    mode,
    prompt,
    stages,
    candidateTitle: mode === 'autonomous' ? '第 13 章 · 封冻之前' : '候选段落 · 第 13 章',
    candidateParagraphs: buildCandidate(mode, prompt, memory),
    memoryDelta: mode === 'autonomous' ? extractMemory(prompt) : [],
    createdAt: new Date().toISOString(),
    engine: 'local-demo',
  };
}

function buildCandidate(
  mode: Exclude<CreationMode, 'manual'>,
  prompt: string,
  memory: MemoryFact[]
): string[] {
  const place = memory.find((item) => item.kind === '世界')?.value ?? '潮汐城';
  const person = memory.find((item) => item.kind === '人物')?.value ?? '林砚';
  const seed = prompt.trim() || '让主角发现值班簿里被涂掉的一行记录';

  if (mode === 'assisted') {
    return [
      `封冻前最后一班船靠岸时，码头上的灯还没有亮。林砚把新抄好的潮位表夹进值班簿，翻到最后一页，才发现纸背有一道很浅的铅笔痕。`,
      `那不是她写的字，也不是前任观测员的笔迹。她拿指尖沿着那道痕往下走，走到一半，窗外传来缆绳收紧的声响。她抬头看了一眼钟——比预报的靠岸时间早了十一分钟。`,
      `这个数字被她记进本子，又在旁边添了一个问号。过去三年里，这艘船从不早到。她想起${place}的旧规矩：封冻之前，所有反常都要写进当日的值班记录。`,
      `（候选基于：${seed}。人物与世界观来自长期记忆；${person}的口吻保持克制，未新增未经确认的设定。）`,
    ];
  }

  return [
    `封冻前最后一班船靠岸时，码头上的灯还没有亮。`,
    `林砚把新抄好的潮位表夹进值班簿，翻到最后一页，才发现纸背有一道很浅的铅笔痕。那不是她写的字，也不是前任观测员的笔迹。她拿指尖沿着那道痕往下走，走到一半，窗外传来缆绳收紧的声响。`,
    `她抬头看了一眼钟。比预报的靠岸时间早了十一分钟。这个数字被她记进本子，又在旁边添了一个问号。过去三年里，这艘船从不早到。`,
    `值班室的门外有人停下脚步。她没有回头，只听见那人把一只封好的纸袋放在门槛上，纸袋摩擦石面的声音很短。等脚步声远了，她才走过去，看见封口上写着父亲的名字。`,
    `潮水正在退。按${place}的旧例，封冻之前，所有反常都要写进当日的值班记录。她翻开本子，把纸袋的出现时间、在场的人与那行被涂掉的记录并排放在一起。`,
    `（纯 AI 工作流候选：情节规划 → 章节起草 → 一致性审校 → 记忆归档。所有新事实均为候选，需作者确认后才会写入长期记忆。）`,
  ];
}

function extractMemory(prompt: string): MemoryFact[] {
  const facts: MemoryFact[] = [
    {
      id: `candidate-${Date.now()}-1`,
      kind: '情节',
      label: '纸袋与父亲',
      value: '值班室门槛出现写有父亲名字的纸袋；出现时间早于预报靠岸十一分钟。',
      locked: false,
    },
  ];
  if (prompt.includes('第三盏灯')) {
    facts.push({
      id: `candidate-${Date.now()}-2`,
      kind: '伏笔',
      label: '第三盏灯',
      value: '第三盏灯与父亲的名字可能指向同一条旧记录。',
      locked: false,
    });
  }
  return facts;
}

export async function runAiWorkflow(
  run: AiRun,
  onUpdate: (next: AiRun) => void
): Promise<AiRun> {
  let current = run;
  for (const stage of run.stages) {
    current = {
      ...current,
      stages: current.stages.map((item) =>
        item.id === stage.id ? { ...item, status: 'running' } : item
      ),
    };
    onUpdate(current);
    await delay(stage.id === 'consistency' || stage.id === 'draft' ? 900 : 520);
    current = {
      ...current,
      stages: current.stages.map((item) =>
        item.id === stage.id ? { ...item, status: 'done' } : item
      ),
    };
    onUpdate(current);
  }
  return current;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}
