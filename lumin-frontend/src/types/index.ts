// 启明书院前端领域类型（与 P0-A 内核概念对齐，仅前端演示用）
// 判定规则集中在 src/lib/demoPolicy.ts；此文件只描述数据结构。

export type AgeBand = 'C0' | 'C1' | 'C2' | 'C3' | 'C4';

export type BookState = 'published' | 'inreview' | 'removed';

/** 5 个工作空间：书城 / 创作工作区 / 审核工作区 / 运营与配置 / 我的 */
export type WorkspaceId = 'catalog' | 'author' | 'reviewer' | 'operator' | 'me';

/** 读者侧状态驱动视图：书城列表 / 书详情 / 阅读器覆盖层 */
export type ViewId = 'R01' | 'R02' | 'R03';

/** 字数档：每档在 12 部示例书目中均非空 */
export type WordBucket = 'all' | 'lt10' | 'w10_30' | 'w30_60' | 'gt60';

export type SortKey = 'recent' | 'title' | 'words';

export type ReadThemeId = 'rice' | 'green' | 'parchment' | 'night';

export type NoticeLevel = 'info' | 'warning' | 'danger';

export interface Chapter {
  title: string;
  /** 本章字数（字），用于 R03 章节元信息 */
  words: number;
  /** 固定基准日期派生的绝对日期，不读系统时钟 */
  updatedAt: string;
}

export interface Book {
  id: string;
  title: string;
  author: string;
  category: string;
  /** 适龄带：门禁唯一依据（见 demoPolicy.isAgeMuted） */
  band: AgeBand;
  /** 周岁门槛仅作展示，不作为门禁来源 */
  minAge: 0 | 14 | 16 | 18;
  state: BookState;
  /** 字数，单位：万字 */
  words: number;
  /** 展示文案，如「41.1 万字」 */
  wt: string;
  /** 绝对日期（固定基准派生） */
  updatedAt: string;
  /** 相对时间，如「3 小时前」 */
  relativeTime: string;
  /** 最近更新距基准的小时数，用于「最近更新」排序 */
  hoursAgo: number;
  /** 书封纸色 / 墨色，仅用于 CSS 排版，无外部图片 */
  cover: [string, string];
  /** 书卡短说明 */
  summary: string;
  /** R02 作品简介（80–120 字原创） */
  intro: string;
  /** 作者签名 */
  sig: string;
  tags: string[];
  /** 8–12 章原创章节 */
  chapters: Chapter[];
}

export interface FilterState {
  age: AgeBand | 'all';
  status: BookState | 'all';
  words: WordBucket;
  sort: SortKey;
}

export interface Draft {
  id: string;
  title: string;
  state: 'EDITABLE' | 'SUBMITTED' | 'DISCARDED';
  words: number;
  updatedAt: string;
}

export interface ReviewItem {
  id: string;
  title: string;
  author: string;
  kind: '机审' | '人审';
  status: 'pending' | 'passed' | 'rejected';
  reason?: string;
}

export interface AuditNotice {
  id: string;
  time: string;
  level: NoticeLevel;
  text: string;
}
