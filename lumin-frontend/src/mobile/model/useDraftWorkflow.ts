import { useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import type { CreationMode, DraftPayload } from '../types';
import { buildDraft } from './drafts';
import { STUDIO_WORKS } from '../data/studio';
import type { MobileRoute } from '../navigation';
import { upgradeModeForInsert } from '../../lib/creationMode';

interface DraftWorkflowOptions {
  draft: DraftPayload | null;
  mode: CreationMode;
  setDraft: Dispatch<SetStateAction<DraftPayload | null>>;
  setMode: Dispatch<SetStateAction<CreationMode>>;
  navigate: (route: MobileRoute) => void;
  showToast: (message: string) => void;
}

/**
 * 草稿动作边界：打开编辑器、保存、插入 AI 候选、提交审核。
 * 只改状态，不直接渲染页面，便于 Web 壳复用同一套流程。
 */
export function useDraftWorkflow({
  draft,
  mode,
  setDraft,
  setMode,
  navigate,
  showToast,
}: DraftWorkflowOptions) {
  const [editorDraft, setEditorDraft] = useState<DraftPayload | null>(null);

  const openEditor = (workId: string, chapterId?: string): void => {
    const work = STUDIO_WORKS.find((item) => item.id === workId) ?? STUDIO_WORKS[0];
    const existing =
      draft && draft.workId === work.id && (draft.chapterId ?? undefined) === (chapterId ?? undefined)
        ? draft
        : null;
    const nextDraft = existing ?? buildDraft(work.id, chapterId, mode);
    setEditorDraft(nextDraft);
    setDraft(nextDraft);
    navigate({
      name: 'editor',
      workId: work.id,
      chapterId: nextDraft.chapterId ?? undefined,
      mode,
    });
  };

  const saveDraft = (next: DraftPayload): void => {
    setDraft(next);
    setEditorDraft(next);
  };

  const openAi = (nextMode?: CreationMode): void => {
    if (nextMode) {
      setMode(nextMode);
    }
    navigate({ name: 'ai' });
  };

  const insertCandidate = (text: string): void => {
    const base = editorDraft ?? draft ?? buildDraft('w1', undefined, 'assisted');
    const freshParagraphs = text
      .split(/\n\n+/)
      .map((paragraph) => paragraph.trim())
      .filter((paragraph) => paragraph.length > 0 && !base.body.includes(paragraph));
    const addition = freshParagraphs.join('\n\n');
    const next: DraftPayload = {
      ...base,
      body: addition ? (base.body ? `${base.body}\n\n${addition}` : addition) : base.body,
      mode: upgradeModeForInsert(base.mode),
      updatedAt: new Date().toISOString(),
    };
    setDraft(next);
    setEditorDraft(next);
    navigate({
      name: 'editor',
      workId: next.workId,
      chapterId: next.chapterId ?? undefined,
      mode: next.mode,
    });
  };

  const publish = (next: DraftPayload): void => {
    setDraft(next);
    setEditorDraft(next);
    showToast('已提交审核：草稿 → 自检 → 审核 → 发布');
  };

  return { editorDraft, openEditor, saveDraft, openAi, insertCandidate, publish };
}
