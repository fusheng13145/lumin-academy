import type { WorkspaceId } from '../types';

/**
 * 哈希路由纯函数（docs/13 UGC-017 一阶段）：作品/章节真实地址、深链、
 * 刷新与前进后退。hash 是唯一地址真相；App 只做解析回灌与导航同步。
 *
 * 路由形态：
 * - `#/`（或 `#/catalog`）→ 书城
 * - `#/work/{id}` → 作品详情（真实 UUID 或演示夹具 id 同形）
 * - `#/read/{id}/{chapterIdx}` → 阅读器（章节为替换式入栈，后退回详情）
 * - `#/author` `#/reviewer` `#/operator` `#/me` → 工作区
 *
 * 未知 hash（如页内锚点 `#main`）返回 null——调用方不动作。
 */
export type AppRoute =
  | { kind: 'workspace'; workspace: WorkspaceId }
  | { kind: 'detail'; workId: string }
  | { kind: 'reader'; workId: string; chapterIdx: number };

const WORKSPACE_HASHES: Record<string, WorkspaceId> = {
  '': 'catalog',
  catalog: 'catalog',
  author: 'author',
  reviewer: 'reviewer',
  operator: 'operator',
  me: 'me',
};

export function parseHash(hash: string): AppRoute | null {
  const path = hash.replace(/^#/, '').replace(/^\/+/, '');
  if (path in WORKSPACE_HASHES) {
    return { kind: 'workspace', workspace: WORKSPACE_HASHES[path] };
  }
  const segments = path.split('/').filter((segment) => segment !== '');
  if (segments[0] === 'work' && segments.length === 2 && segments[1] !== '') {
    return { kind: 'detail', workId: segments[1] };
  }
  if (segments[0] === 'read' && segments.length === 3 && segments[1] !== '') {
    const chapterIdx = Number(segments[2]);
    if (Number.isInteger(chapterIdx) && chapterIdx >= 0) {
      return { kind: 'reader', workId: segments[1], chapterIdx };
    }
    // 章节段非法 → 详情兜底（仍是有意义的真实地址）
    return { kind: 'detail', workId: segments[1] };
  }
  return null;
}

export function toHash(route: AppRoute): string {
  switch (route.kind) {
    case 'workspace':
      return route.workspace === 'catalog' ? '#/' : `#/${route.workspace}`;
    case 'detail':
      return `#/work/${route.workId}`;
    case 'reader':
      return `#/read/${route.workId}/${route.chapterIdx}`;
  }
}
