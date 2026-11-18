import { useState } from 'react';
import type { AiRun, CreationMode, MemoryFact } from '../types';
import { MODE_DESC, MODE_LABEL, createAiRun, runAiWorkflow } from '../services/aiDemo';
import { Sheet, StatusBadge, TopBar } from '../components/ui';
import {
  IconBrain,
  IconCheck,
  IconLibrary,
  IconLock,
  IconRefresh,
  IconSparkles,
} from '../components/Icons';

interface AiScreenProps {
  mode: CreationMode;
  memory: MemoryFact[];
  onModeChange: (mode: CreationMode) => void;
  onBack: () => void;
  onInsert: (text: string) => void;
  onMemoryAdd: (facts: MemoryFact[]) => void;
  onToast: (message: string) => void;
  onMaterials: () => void;
  onIllustration: () => void;
}

const DEFAULT_PROMPT =
  '让林砚在封冻前的值班室发现一条被涂掉的救援记录。保持克制的海雾质感，不新增未经确认的世界观设定。';

export function AiScreen({
  mode,
  memory,
  onModeChange,
  onBack,
  onInsert,
  onMemoryAdd,
  onToast,
  onMaterials,
  onIllustration,
}: AiScreenProps) {
  const [prompt, setPrompt] = useState(DEFAULT_PROMPT);
  const [run, setRun] = useState<AiRun | null>(null);
  const [running, setRunning] = useState(false);
  const [memoryOpen, setMemoryOpen] = useState(false);

  const effectiveMode = mode === 'manual' ? 'assisted' : mode;

  function switchMode(next: CreationMode): void {
    if (next === mode) {
      return;
    }
    setRun(null);
    setRunning(false);
    onModeChange(next);
  }

  async function startRun(): Promise<void> {
    if (mode === 'manual') {
      onToast('传统写作模式不调用 AI；请先切换到 AI 辅助或纯 AI');
      return;
    }
    if (running) {
      return;
    }
    const next = createAiRun(effectiveMode, prompt, memory);
    setRun(next);
    setRunning(true);
    try {
      const finished = await runAiWorkflow(next, setRun);
      setRun(finished);
      onToast('工作流完成：候选章节等待你的确认');
    } finally {
      setRunning(false);
    }
  }

  function acceptCandidate(): void {
    if (!run) {
      return;
    }
    const text = run.candidateParagraphs
      .filter((paragraph) => !paragraph.startsWith('（'))
      .join('\n\n');
    onInsert(text);
    if (run.memoryDelta.length > 0) {
      onMemoryAdd(run.memoryDelta);
    }
    onToast(run.mode === 'autonomous' ? '已写入编辑器，并将 1 条候选记忆加入待确认队列' : '候选段落已写入编辑器');
  }

  function stageStatusLabel(status: string): string {
    if (status === 'running') {
      return '进行中';
    }
    if (status === 'done') {
      return '完成';
    }
    return '等待';
  }

  return (
    <div className="m-page m-enter">
      <TopBar
        title="AI 创作台"
        subtitle="CO-CREATION"
        onBack={onBack}
        right={<span className="m-badge" style={{ marginRight: 4 }}>本地演示</span>}
      />

      <div className="m-mode-tabs" role="tablist" aria-label="创作模式">
        {(['manual', 'assisted', 'autonomous'] as CreationMode[]).map((item) => (
          <button
            key={item}
            className={`m-mode-tab ${mode === item ? 'is-active' : ''}`.trim()}
            type="button"
            role="tab"
            aria-selected={mode === item}
            onClick={() => switchMode(item)}
          >
            {MODE_LABEL[item]}
          </button>
        ))}
      </div>

      <p className="m-caption" style={{ marginTop: 10 }}>
        {MODE_DESC[mode]}
      </p>

      <section className="m-section" style={{ marginTop: 20 }}>
        <div className="m-section-head">
          <div className="m-section-head__main">
            <p className="m-eyebrow">LONG-TERM MEMORY</p>
            <h2 className="m-h2">长期记忆</h2>
          </div>
          <button className="m-section-head__action" type="button" onClick={() => setMemoryOpen(true)}>
            查看全部
          </button>
        </div>
        <div className="m-memory">
          {memory.slice(0, 6).map((fact) => (
            <div className="m-memory__item" key={fact.id}>
              <span className="m-memory__label">
                {fact.kind} · {fact.locked ? '已锁定' : '待确认'}
              </span>
              <span className="m-memory__value">{fact.label}</span>
            </div>
          ))}
        </div>
        <p className="m-caption">
          记忆以「事实条目」持久化，而不是无限拼接上下文；每次生成前按人物、世界观与伏笔检索。
        </p>
      </section>

      {mode === 'manual' ? (
        <section className="m-section">
          <div className="m-card m-card--pad">
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <IconBrain size={22} />
              <div>
                <p className="m-h3">传统写作模式</p>
                <p className="m-caption" style={{ marginTop: 3 }}>AI 不介入正文，编辑器保持纯手写体验。</p>
              </div>
            </div>
            <hr className="m-rule" style={{ margin: '14px 0' }} />
            <p className="m-body">
              你仍然可以使用素材库、灵感收集与数据看板；AI 入口保留但不调用。
              如果想让 AI 参与，切换到「AI 辅助」或「纯 AI」。
            </p>
            <button className="m-btn m-btn--primary m-btn--block" type="button" onClick={onBack} style={{ marginTop: 14 }}>
              回到编辑器
            </button>
          </div>
        </section>
      ) : (
        <>
          <section className="m-section">
            <div className="m-section-head">
              <div className="m-section-head__main">
                <p className="m-eyebrow">BRIEF</p>
                <h2 className="m-h2">这次想写什么</h2>
              </div>
            </div>
            <textarea
              className="m-ai-prompt"
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              placeholder="描述本章目标、冲突或想保留的细节"
              aria-label="创作指令"
            />
            <p className="m-ai-hint">
              {mode === 'autonomous'
                ? '纯 AI：由服务端 workflow 调度规划、起草、一致性审校与记忆归档 Agent。'
                : 'AI 辅助：只生成候选段落，正文是否采纳由你决定。'}
            </p>
            <button
              className="m-btn m-btn--primary m-btn--block"
              type="button"
              onClick={() => void startRun()}
              disabled={running}
              style={{ marginTop: 12 }}
            >
              <IconSparkles size={17} />
              {running ? '工作流运行中…' : '开始生成候选'}
            </button>
          </section>

          {mode === 'autonomous' ? (
            <section className="m-section">
              <div className="m-card m-card--pad">
                <p className="m-eyebrow">WORKFLOW + MULTI-AGENT</p>
                <div className="m-mode-grid">
                  {[
                    ['规划 Agent', '拆章节目标与冲突节拍'],
                    ['起草 Agent', '按场景产出草稿'],
                    ['审校 Agent', '比对人物口吻与前文事实'],
                    ['记忆 Agent', '归档新事实与伏笔'],
                  ].map(([title, desc]) => (
                    <div key={title} className="m-mode-card">
                      <span className="m-mode-card__title">{title}</span>
                      <span className="m-mode-card__desc">{desc}</span>
                    </div>
                  ))}
                </div>
                <p className="m-caption" style={{ marginTop: 10 }}>
                  移动端不运行 Agent 循环；它只负责任务台、候选确认与记忆查看，避免在 WebView 内伪造“生成中”的假象。
                </p>
              </div>
            </section>
          ) : null}

          {run ? (
            <section className="m-section">
              <div className="m-section-head">
                <div className="m-section-head__main">
                  <p className="m-eyebrow">AGENT TRACE</p>
                  <h2 className="m-h2">工作流进度</h2>
                </div>
                <StatusBadge label={running ? '运行中' : '已完成'} tone={running ? 'accent' : 'green'} />
              </div>
              <div className="m-card m-card--pad">
                <div className="m-ai-stages">
                  {run.stages.map((stage) => (
                    <div className="m-ai-stage" key={stage.id}>
                      <div className="m-ai-stage__rail">
                        <span className={`m-ai-stage__dot ${stage.status === 'running' ? 'is-running' : stage.status === 'done' ? 'is-done' : ''}`.trim()} />
                        <span className="m-ai-stage__line" />
                      </div>
                      <div className="m-ai-stage__label">
                        <span className={stage.status === 'running' ? 'is-active' : ''}>{stage.label}</span>
                        <span className="m-caption" style={{ display: 'block' }}>{stage.detail}</span>
                      </div>
                      <span className="m-ai-stage__status">{stageStatusLabel(stage.status)}</span>
                    </div>
                  ))}
                </div>
              </div>

              <div className="m-ai-candidate">
                <div className="m-ai-candidate__head">
                  <div>
                    <p className="m-eyebrow" style={{ margin: 0 }}>CANDIDATE</p>
                    <p className="m-h3" style={{ marginTop: 3 }}>{run.candidateTitle}</p>
                  </div>
                  <StatusBadge label={run.engine === 'local-demo' ? '演示引擎' : '服务端'} />
                </div>
                <div className="m-ai-candidate__body">
                  {run.candidateParagraphs.map((paragraph, index) => (
                    <p key={`${run.id}-${index}`}>{paragraph}</p>
                  ))}
                </div>
                <div className="m-ai-candidate__foot">
                  <button className="m-btn m-btn--sm m-btn--primary" type="button" onClick={acceptCandidate} disabled={running}>
                    <IconCheck size={15} />
                    采纳并写入
                  </button>
                  <button className="m-btn m-btn--sm" type="button" onClick={() => void startRun()} disabled={running}>
                    <IconRefresh size={15} />
                    重新生成
                  </button>
                  <button
                    className="m-btn m-btn--sm"
                    type="button"
                    onClick={() => {
                      setRun(null);
                      onToast('已退回候选，原稿未改动');
                    }}
                    disabled={running}
                  >
                    退回
                  </button>
                </div>
              </div>

              {run.memoryDelta.length > 0 ? (
                <div className="m-card m-card--pad" style={{ marginTop: 12 }}>
                  <p className="m-eyebrow">MEMORY DELTA</p>
                  {run.memoryDelta.map((fact) => (
                    <div className="m-sheet__row" key={fact.id}>
                      <div>
                        <p className="m-h3" style={{ margin: 0 }}>{fact.label}</p>
                        <p className="m-caption" style={{ marginTop: 3 }}>{fact.value}</p>
                      </div>
                      <span className="m-badge">待确认</span>
                    </div>
                  ))}
                  <p className="m-caption" style={{ marginTop: 10 }}>
                    新事实先进入候选区，不直接覆盖已锁定记忆；确认后才进入后续生成上下文。
                  </p>
                </div>
              ) : null}
            </section>
          ) : (
            <section className="m-section">
              <div className="m-card m-card--pad">
                <p className="m-eyebrow">HOW IT WORKS</p>
                <div className="m-mode-grid" style={{ marginTop: 10 }}>
                  {[
                    ['读取记忆', '先取人物、世界观与前情，避免“失忆”。'],
                    ['生成候选', mode === 'autonomous' ? '多 Agent 分工产出，不直接改正文。' : '只生成候选段落，不改正文。'],
                    ['作者确认', '入稿、退回或重写都由作者决定。'],
                    ['归档记忆', '新事实进入候选区，确认后才持久化。'],
                  ].map(([title, desc]) => (
                    <div className="m-mode-card" key={title}>
                      <span className="m-mode-card__title">{title}</span>
                      <span className="m-mode-card__desc">{desc}</span>
                    </div>
                  ))}
                </div>
              </div>
            </section>
          )}
        </>
      )}

      <MemorySheet open={memoryOpen} onClose={() => setMemoryOpen(false)} memory={memory} />

      <button className="m-card m-card--pad" type="button" onClick={onMaterials} style={{ marginTop: 24, width: '100%', textAlign: 'left', color: 'inherit', display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{ width: 38, height: 38, borderRadius: 10, background: 'var(--m-paper)', color: 'var(--m-ink)', display: 'grid', placeItems: 'center', flex: 'none' }}>
          <IconLibrary size={18} />
        </div>
        <div style={{ flex: 1 }}>
          <p className="m-h3">素材库与灵感</p>
          <p className="m-caption" style={{ marginTop: 3 }}>
            取材、记灵感、回查引用记录，再交给 AI 或自己写。
          </p>
        </div>
        <span className="m-badge">打开</span>
      </button>

      <button className="m-card m-card--pad" type="button" onClick={onIllustration} style={{ marginTop: 10, width: '100%', textAlign: 'left', color: 'inherit', display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{ width: 38, height: 38, borderRadius: 10, background: 'var(--m-accent-soft)', color: 'var(--m-accent-deep)', display: 'grid', placeItems: 'center', flex: 'none' }}>
          <IconSparkles size={18} />
        </div>
        <div style={{ flex: 1 }}>
          <p className="m-h3">AI 插图</p>
          <p className="m-caption" style={{ marginTop: 3 }}>
            为章节生成概念图 / 书封草案；当前为本地演示。
          </p>
        </div>
        <span className="m-badge">打开</span>
      </button>

      <div className="m-card m-card--pad" style={{ marginTop: 12, display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{ width: 38, height: 38, borderRadius: 10, background: 'var(--m-accent-soft)', color: 'var(--m-accent-deep)', display: 'grid', placeItems: 'center', flex: 'none' }}>
          <IconLock size={18} />
        </div>
        <div style={{ flex: 1 }}>
          <p className="m-h3">内容版权归作者</p>
          <p className="m-caption" style={{ marginTop: 3 }}>
            AI 生成来源随稿记录，可审计；平台不因提供工具而主张正文著作权。
          </p>
        </div>
      </div>
    </div>
  );
}

function MemorySheet({
  open,
  onClose,
  memory,
}: {
  open: boolean;
  onClose: () => void;
  memory: MemoryFact[];
}) {
  return (
    <Sheet open={open} onClose={onClose} title="长期记忆">
      <p className="m-caption" style={{ marginBottom: 10 }}>
        {memory.length} 条事实 · {memory.filter((fact) => fact.locked).length} 条已锁定
      </p>
      {memory.map((fact) => (
        <div className="m-sheet__row" key={fact.id}>
          <div>
            <p className="m-h3" style={{ margin: 0 }}>
              {fact.kind} · {fact.label}
            </p>
            <p className="m-caption" style={{ marginTop: 3 }}>{fact.value}</p>
          </div>
          <span className="m-badge">{fact.locked ? '锁定' : '待确认'}</span>
        </div>
      ))}
      <p className="m-caption" style={{ marginTop: 12 }}>
        锁定记忆只能由作者在 Web 端“记忆审阅”中修改；移动端本期只读，避免误改关键设定。
      </p>
    </Sheet>
  );
}
