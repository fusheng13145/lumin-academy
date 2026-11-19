import { describe, expect, it } from 'vitest';
import { parseHash, toHash } from './route';

describe('route（UGC-017 哈希路由）', () => {
  it('解析工作区路由（含空 hash 与斜杠变体）', () => {
    expect(parseHash('')).toEqual({ kind: 'workspace', workspace: 'catalog' });
    expect(parseHash('#/')).toEqual({ kind: 'workspace', workspace: 'catalog' });
    expect(parseHash('#catalog')).toEqual({ kind: 'workspace', workspace: 'catalog' });
    expect(parseHash('#/me')).toEqual({ kind: 'workspace', workspace: 'me' });
    expect(parseHash('#/reviewer')).toEqual({ kind: 'workspace', workspace: 'reviewer' });
    expect(parseHash('#/operator')).toEqual({ kind: 'workspace', workspace: 'operator' });
    expect(parseHash('#/author')).toEqual({ kind: 'workspace', workspace: 'author' });
  });

  it('解析详情与阅读器路由（真实地址）', () => {
    expect(parseHash('#/work/abc-123')).toEqual({ kind: 'detail', workId: 'abc-123' });
    expect(parseHash('#/read/abc-123/2'))
      .toEqual({ kind: 'reader', workId: 'abc-123', chapterIdx: 2 });
    expect(parseHash('#/read/abc-123/0')).toEqual({ kind: 'reader', workId: 'abc-123', chapterIdx: 0 });
  });

  it('非法输入返回 null 或详情兜底（不抛错）', () => {
    expect(parseHash('#main')).toBeNull();
    expect(parseHash('#/nonexistent')).toBeNull();
    expect(parseHash('#/work/')).toBeNull();
    // 章节段非法 → 详情兜底（有意义的真实地址不丢）
    expect(parseHash('#/read/abc-123/x')).toEqual({ kind: 'detail', workId: 'abc-123' });
    expect(parseHash('#/read/abc-123/-1')).toEqual({ kind: 'detail', workId: 'abc-123' });
  });

  it('toHash 与 parseHash 互逆', () => {
    const routes = [
      { kind: 'workspace', workspace: 'catalog' },
      { kind: 'workspace', workspace: 'me' },
      { kind: 'detail', workId: 'w-1' },
      { kind: 'reader', workId: 'w-1', chapterIdx: 3 },
    ] as const;
    for (const route of routes) {
      expect(parseHash(toHash(route))).toEqual(route);
    }
    expect(toHash({ kind: 'workspace', workspace: 'catalog' })).toBe('#/');
  });
});
