export const MOBILE_TABS = ['home', 'studio', 'ai', 'me'] as const;
export type MobileTab = (typeof MOBILE_TABS)[number];

export type MobileRoute =
  | { name: 'home' }
  | { name: 'search' }
  | { name: 'book'; bookId: string }
  | { name: 'reader'; bookId: string; chapterNo: number }
  | { name: 'studio' }
  | { name: 'work'; workId: string }
  | { name: 'editor'; workId: string; chapterId?: string; mode?: 'manual' | 'assisted' | 'autonomous' }
  | { name: 'ai'; workId?: string; chapterId?: string }
  | { name: 'materials' }
  | { name: 'illustration' }
  | { name: 'me' }
  | { name: 'settings' }
  | { name: 'comments' };

export interface RouteMeta {
  title: string;
  subtitle: string;
}

export const ROUTE_META: Record<MobileRoute['name'], RouteMeta> = {
  home: { title: '启明书院', subtitle: 'LUMIN ACADEMY' },
  search: { title: '搜索', subtitle: 'FIND A STORY' },
  book: { title: '作品详情', subtitle: 'WORK' },
  reader: { title: '阅读', subtitle: 'READER' },
  studio: { title: '作者工作室', subtitle: 'STUDIO' },
  work: { title: '作品管理', subtitle: 'WORKBENCH' },
  editor: { title: '写作中心', subtitle: 'WRITING' },
  ai: { title: 'AI 创作台', subtitle: 'CO-CREATION' },
  materials: { title: '素材库与灵感', subtitle: 'MATERIALS' },
  illustration: { title: 'AI 插图', subtitle: 'VISUAL' },
  me: { title: '我的', subtitle: 'ACCOUNT' },
  settings: { title: '阅读设置', subtitle: 'PREFERENCES' },
  comments: { title: '评论管理', subtitle: 'COMMENTS' },
};

export const TAB_LABEL: Record<MobileTab, string> = {
  home: '书城',
  studio: '创作',
  ai: 'AI',
  me: '我的',
};

export function isTabRoute(name: MobileRoute['name']): name is MobileTab {
  return (MOBILE_TABS as readonly string[]).includes(name);
}
