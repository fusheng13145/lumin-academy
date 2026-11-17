import type { AgeBand, Book, Chapter } from '../types';
import type { ChapterEntryDto, WorkDetailDto } from '../services/readerService';

/**
 * 真实作品 → 演示视图适配（R02/R03 复用现有排版组件）。
 * 适龄门禁的真实权威在后端（三态裁决，未授权 404）；
 * 此处 grade→band 映射仅用于展示层灰显提示，与后端口径一致。
 */
const GRADE_BAND: Record<string, AgeBand> = {
  G: 'C0',
  PG_12: 'C1',
  PG_16: 'C2',
  A: 'C4',
};

/** CSS 排版书封纸色/墨色，按索引轮转，无外部图片。 */
const COVER_PALETTE: [string, string][] = [
  ['var(--paper-rice, #f7f3e9)', '#3c3a36'],
  ['var(--paper-green, #eef3ec)', '#2f4436'],
  ['var(--paper-parchment, #f3ead9)', '#5a4632'],
];

export function gradeBand(grade: string): AgeBand {
  return GRADE_BAND[grade] ?? 'C0';
}

export function gradeLabel(grade: string): string {
  return grade === 'PG_12' ? 'PG-12' : grade === 'PG_16' ? 'PG-16' : grade;
}

/** 真实章节目录 → 演示章节视图（视图层自带「第 N 章」前缀，目录无章节名）。 */
function toChapters(entries: ChapterEntryDto[]): Chapter[] {
  return entries.map(() => ({
    title: '',
    words: 0,
    updatedAt: '',
  }));
}

export function realBook(
  detail: WorkDetailDto,
  chapters: ChapterEntryDto[],
  index: number,
): Book {
  const cover = COVER_PALETTE[index % COVER_PALETTE.length];
  return {
    id: detail.workId,
    title: detail.title,
    author: detail.author,
    category: gradeLabel(detail.grade),
    band: gradeBand(detail.grade),
    minAge: detail.grade === 'A' ? 18 : detail.grade === 'PG_16' ? 16 : detail.grade === 'PG_12' ? 14 : 0,
    state: 'published',
    words: 0,
    wt: '',
    updatedAt: '',
    relativeTime: '',
    hoursAgo: 0,
    cover,
    summary: detail.synopsis ?? '',
    intro: detail.synopsis ?? '',
    sig: '',
    tags: detail.tags,
    chapters: toChapters(chapters),
  };
}
