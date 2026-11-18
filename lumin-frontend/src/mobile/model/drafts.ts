import type { CreationMode, DraftPayload, ReaderSettings } from '../types';
import { DEFAULT_DRAFT, STUDIO_WORKS } from '../data/studio';
import { findBook } from '../data/library';

export const DEFAULT_SETTINGS: ReaderSettings = {
  theme: 'rice',
  fontSize: 19,
  lineHeight: 1.95,
  fontFamily: 'serif',
};

/** 编辑器草稿的唯一构建入口；Web 壳接入真实 API 时替换此函数即可。 */
export function buildDraft(
  workId: string,
  chapterId: string | undefined,
  mode: CreationMode
): DraftPayload {
  const work = STUDIO_WORKS.find((item) => item.id === workId) ?? STUDIO_WORKS[0];
  const book = findBook(work.bookId);
  const chapter = chapterId ? book?.chapters.find((item) => item.id === chapterId) : undefined;
  return {
    workId,
    chapterId: chapter?.id ?? null,
    title: chapter ? `第 ${chapter.no} 章 · ${chapter.title}` : DEFAULT_DRAFT.title,
    body: chapter ? chapter.body.join('\n\n') : DEFAULT_DRAFT.body,
    mode,
    updatedAt: new Date().toISOString(),
  };
}
