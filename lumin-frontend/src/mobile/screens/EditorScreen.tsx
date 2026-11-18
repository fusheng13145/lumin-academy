import { useCallback, useEffect, useRef, useState } from 'react';
import type { CreationMode, DraftPayload, MobileChapter, StudioWork } from '../types';
import { MODE_DESC, MODE_LABEL } from '../../lib/creationMode';
import { Sheet } from '../components/ui';
import {
  IconBack,
  IconCheck,
  IconSave,
  IconSend,
  IconSparkles,
  IconWand,
} from '../components/Icons';

interface EditorScreenProps {
  work: StudioWork;
  chapter: MobileChapter | null;
  mode: CreationMode;
  initialDraft: DraftPayload | null;
  onBack: () => void;
  onSaveDraft: (draft: DraftPayload) => void;
  onOpenAi: (mode: CreationMode) => void;
  onPublish: (draft: DraftPayload) => void;
  onModeChange: (mode: CreationMode) => void;
  onToast: (message: string) => void;
}

type SaveState = 'saved' | 'dirty' | 'saving';

export function EditorScreen({
  work,
  chapter,
  mode,
  initialDraft,
  onBack,
  onSaveDraft,
  onOpenAi,
  onPublish,
  onModeChange,
  onToast,
}: EditorScreenProps) {
  const [title, setTitle] = useState(
    initialDraft?.title || (chapter ? `第 ${chapter.no} 章 · ${chapter.title}` : '新章节')
  );
  const [body, setBody] = useState(initialDraft?.body ?? '');
  const [saveState, setSaveState] = useState<SaveState>('saved');
  const [lastSaved, setLastSaved] = useState(initialDraft?.updatedAt ?? '');
  const [modeOpen, setModeOpen] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const autoSaveTimer = useRef<number | null>(null);

  const wordCount = body.replace(/\s/g, '').length;

  const persist = useCallback(
    (nextTitle: string, nextBody: string): void => {
      if (autoSaveTimer.current) {
        window.clearTimeout(autoSaveTimer.current);
        autoSaveTimer.current = null;
      }
      setSaveState('saving');
      const payload: DraftPayload = {
        workId: work.id,
        chapterId: chapter?.id ?? null,
        title: nextTitle.trim() || '未命名章节',
        body: nextBody,
        mode,
        updatedAt: new Date().toISOString(),
      };
      onSaveDraft(payload);
      window.setTimeout(() => {
        setSaveState('saved');
        setLastSaved(payload.updatedAt);
      }, 260);
    },
    [chapter?.id, mode, onSaveDraft, work.id]
  );

  const queueSave = useCallback(
    (nextTitle: string, nextBody: string): void => {
      setSaveState('dirty');
      if (autoSaveTimer.current) {
        window.clearTimeout(autoSaveTimer.current);
      }
      autoSaveTimer.current = window.setTimeout(() => persist(nextTitle, nextBody), 1000);
    },
    [persist]
  );

  useEffect(() => {
    return () => {
      if (autoSaveTimer.current) {
        window.clearTimeout(autoSaveTimer.current);
      }
    };
  }, []);

  function handleTitleChange(value: string): void {
    setTitle(value);
    queueSave(value, body);
  }

  function handleBodyChange(value: string): void {
    setBody(value);
    queueSave(title, value);
  }

  function handleBack(): void {
    persist(title, body);
    onBack();
  }

  function handlePublish(): void {
    if (body.trim().length < 20) {
      onToast('正文太短，至少写满 20 字再发布');
      return;
    }
    setPublishOpen(true);
  }

  function confirmPublish(): void {
    persist(title, body);
    setPublishOpen(false);
    onPublish({
      workId: work.id,
      chapterId: chapter?.id ?? null,
      title,
      body,
      mode,
      updatedAt: new Date().toISOString(),
    });
  }

  const saveLabel =
    saveState === 'saving'
      ? '正在保存…'
      : saveState === 'dirty'
        ? '有未保存修改'
        : lastSaved
          ? `已保存 ${new Date(lastSaved).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
          : '已保存';

  return (
    <div className="m-editor">
      <header className="m-editor__bar">
        <div className="m-editor__bar-inner">
          <button className="m-icon-btn" type="button" onClick={handleBack} aria-label="返回">
            <IconBack />
          </button>
          <div style={{ flex: 1, minWidth: 0 }}>
            <p className="m-eyebrow" style={{ margin: 0 }}>
              {work.title} · 写作中心
            </p>
            <p className="m-caption" style={{ marginTop: 2 }}>
              {chapter ? `第 ${chapter.no} 章` : '新章节'} · {MODE_LABEL[mode]}
            </p>
          </div>
          <button
            className={`m-editor__status ${saveState !== 'saved' ? 'is-dirty' : ''}`.trim()}
            type="button"
            onClick={() => persist(title, body)}
            style={{ background: 'transparent', border: 0, font: 'inherit' }}
          >
            {saveLabel}
          </button>
          <button className="m-icon-btn" type="button" onClick={() => persist(title, body)} aria-label="保存草稿">
            <IconSave size={19} />
          </button>
        </div>
      </header>

      <div className="m-editor__paper">
        <p className="m-caption" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span className="m-dot" style={{ color: 'var(--m-accent)' }} />
          {MODE_DESC[mode]}
        </p>
        <input
          className="m-editor__title"
          value={title}
          onChange={(event) => handleTitleChange(event.target.value)}
          placeholder="章节标题"
          aria-label="章节标题"
        />
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--m-ink-3)' }}>
          <span className="m-caption">正文</span>
          <span className="m-caption">·</span>
          <span className="m-caption">{wordCount} 字</span>
        </div>
        <textarea
          className="m-editor__body"
          value={body}
          onChange={(event) => handleBodyChange(event.target.value)}
          placeholder="从一个具体的动作、声音或光线开始。"
          aria-label="章节正文"
          spellCheck={false}
        />
      </div>

      <div className="m-editor__dock">
        <div className="m-editor__dock-inner">
          <span className="m-editor__wordcount">{wordCount} 字</span>
          <button className="m-btn m-btn--sm" type="button" onClick={() => setModeOpen(true)}>
            <IconWand size={15} />
            模式
          </button>
          <button className="m-btn m-btn--sm m-btn--soft" type="button" onClick={() => onOpenAi(mode)}>
            <IconSparkles size={15} />
            AI 协助
          </button>
          <button className="m-btn m-btn--sm m-btn--primary" type="button" onClick={handlePublish}>
            <IconSend size={15} />
            发布
          </button>
        </div>
      </div>

      <Sheet open={modeOpen} onClose={() => setModeOpen(false)} title="选择创作模式">
        <p className="m-caption" style={{ marginBottom: 12 }}>
          模式只影响 AI 介入程度，不改变作品著作权归属。切换后可随时切回。
        </p>
        {(['manual', 'assisted', 'autonomous'] as CreationMode[]).map((item) => (
          <button
            key={item}
            className="m-row"
            type="button"
            style={{ borderBottom: '1px solid var(--m-line)' }}
            onClick={() => {
              onModeChange(item);
              setModeOpen(false);
              if (item === 'manual') {
                onToast('已切换到传统写作，AI 不介入正文');
                return;
              }
              onOpenAi(item);
            }}
          >
            <span className="m-row__main">
              <span className="m-row__title">
                {MODE_LABEL[item]}
                {mode === item ? ' · 当前' : ''}
              </span>
              <span className="m-row__meta">{MODE_DESC[item]}</span>
            </span>
            {mode === item ? <IconCheck size={17} /> : null}
          </button>
        ))}
        <div
          className="m-card m-card--pad"
          style={{ marginTop: 14, background: 'var(--m-paper)', borderColor: 'var(--m-line)' }}
        >
          <p className="m-caption" style={{ margin: 0 }}>
            纯 AI 模式由服务端 workflow + multi_agent 执行；移动端只负责任务发起、候选审阅与记忆确认，不在本地伪造生成结果。
          </p>
        </div>
      </Sheet>

      <Sheet open={publishOpen} onClose={() => setPublishOpen(false)} title="提交发布">
        <div className="m-sheet__row">
          <span className="m-sheet__label">章节</span>
          <span className="m-sheet__value">{title || '未命名章节'}</span>
        </div>
        <div className="m-sheet__row">
          <span className="m-sheet__label">字数</span>
          <span className="m-sheet__value">{wordCount} 字</span>
        </div>
        <div className="m-sheet__row">
          <span className="m-sheet__label">创作模式</span>
          <span className="m-sheet__value">{MODE_LABEL[mode]}</span>
        </div>
        <div className="m-sheet__row">
          <span className="m-sheet__label">AI 来源标记</span>
          <span className="m-sheet__value">{mode === 'manual' ? '无 AI 参与' : '随稿记录，可审计'}</span>
        </div>
        <p className="m-caption" style={{ marginTop: 14 }}>
          提交后进入自检与审核；审核通过前不会对外可见。AI 生成内容不会因为发布而改变著作权归属。
        </p>
        <button className="m-btn m-btn--primary m-btn--block" type="button" onClick={confirmPublish} style={{ marginTop: 14 }}>
          确认提交
        </button>
        <button className="m-btn m-btn--block" type="button" onClick={() => setPublishOpen(false)} style={{ marginTop: 8 }}>
          继续修改
        </button>
      </Sheet>
    </div>
  );
}
