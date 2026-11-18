import type { Book, Chapter } from '../types';

/** 三种创作模式：与 docs/15 的 creation_mode 口径一致。 */
export type CreationMode = 'manual' | 'assisted' | 'autonomous';

export interface MobileChapter extends Chapter {
  id: string;
  no: number;
  /** 本地演示正文；真实后端接入时由 chapter content 接口替换。 */
  body: string[];
}

export interface MobileBook extends Omit<Book, 'chapters'> {
  chapters: MobileChapter[];
  /** 首页/详情页的运营文案，不参与正文事实。 */
  editorNote: string;
  featured?: boolean;
  editorPick?: boolean;
  stats: {
    reads: string;
    favorites: string;
    recommend: number;
    revenue: string;
  };
}

export interface StudioWork {
  id: string;
  bookId: string;
  title: string;
  subtitle: string;
  status: 'serial' | 'draft' | 'review' | 'completed';
  statusLabel: string;
  chapterCount: number;
  words: string;
  reads: string;
  favorites: string;
  revenue: string;
  updatedAt: string;
  cover: [string, string];
}

export interface StudioTask {
  id: string;
  title: string;
  meta: string;
  tone: 'accent' | 'green' | 'neutral';
  actionLabel: string;
  target: 'editor' | 'ai' | 'comments';
}

export interface DraftPayload {
  workId: string;
  chapterId: string | null;
  title: string;
  body: string;
  mode: CreationMode;
  updatedAt: string;
}

export interface ReaderSettings {
  theme: 'paper' | 'rice' | 'green' | 'night';
  fontSize: number;
  lineHeight: number;
  fontFamily: 'serif' | 'sans';
}

export interface ReadingProgress {
  bookId: string;
  chapterNo: number;
  percent: number;
  updatedAt: string;
}

export interface MemoryFact {
  id: string;
  kind: '世界' | '人物' | '情节' | '伏笔';
  label: string;
  value: string;
  locked: boolean;
}

export type AiStageStatus = 'pending' | 'running' | 'done';

export interface AiStage {
  id: string;
  label: string;
  detail: string;
  status: AiStageStatus;
}

export interface AiRun {
  id: string;
  mode: CreationMode;
  prompt: string;
  stages: AiStage[];
  candidateTitle: string;
  candidateParagraphs: string[];
  memoryDelta: MemoryFact[];
  createdAt: string;
  engine: 'local-demo' | 'remote-provider';
}
