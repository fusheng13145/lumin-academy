import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CatalogView } from './CatalogView';
import type { WorkListItemDto } from '../../services/readerService';

const works: WorkListItemDto[] = [
  { versionId: 'v-1', workId: 'id-1', title: '山间来信', author: '林舟', grade: 'G', chapterId: 'c-1' },
  { versionId: 'v-2', workId: 'id-2', title: '海雾纪事', author: '阿澜', grade: 'G', chapterId: 'c-2' },
];

const envelope = (data: unknown, code = 'OK') => new Response(JSON.stringify({
  code, message: code === 'OK' ? '' : '依赖不可用', data, traceId: 'search-test',
}), { status: code === 'OK' ? 200 : 503, headers: { 'Content-Type': 'application/json' } });

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockImplementation(async (input) => {
    const url = String(input);
    if (url === '/api/v1/works') {
      return envelope(works);
    }
    if (url.startsWith('/api/v1/works/search')) {
      const q = new URL(url, 'http://test.local').searchParams.get('q') ?? '';
      if (q === '触发错误') {
        return envelope(null, 'D2001');
      }
      const hits = works.filter((work) => work.title.includes(q) || work.author.includes(q));
      return envelope(hits);
    }
    throw new Error(`Unexpected request: ${url}`);
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function renderCatalog() {
  return render(<CatalogView source="live" ageBand="C3" announce={() => {}}
    onOpenDetail={() => {}} onSourceChange={() => {}} />);
}

describe('CatalogView 元数据搜索（UGC-038）', () => {
  it('默认浏览模式展示作品列表', async () => {
    renderCatalog();
    expect(await screen.findByText('山间来信')).toBeTruthy();
    expect(screen.getByText('海雾纪事')).toBeTruthy();
  });

  it('按标题子串搜索并只显示命中项', async () => {
    const user = userEvent.setup();
    renderCatalog();
    await screen.findByText('山间来信');
    await user.type(screen.getByLabelText('搜索作品'), '山间');
    await user.click(screen.getByRole('button', { name: '搜索' }));
    expect(await screen.findByText('山间来信')).toBeTruthy();
    expect(screen.queryByText('海雾纪事')).toBeNull();
  });

  it('空结果与浏览空态分开：明确「没有找到」文案', async () => {
    const user = userEvent.setup();
    renderCatalog();
    await screen.findByText('山间来信');
    await user.type(screen.getByLabelText('搜索作品'), '查无此书');
    await user.click(screen.getByRole('button', { name: '搜索' }));
    expect(await screen.findByText('没有找到与「查无此书」相关的作品')).toBeTruthy();
    // 清除搜索回到浏览模式
    await user.click(screen.getByRole('button', { name: '清除搜索' }));
    expect(await screen.findByText('山间来信')).toBeTruthy();
  });

  it('搜索不可用与空结果分开：请求失败给出「搜索加载失败」', async () => {
    const user = userEvent.setup();
    renderCatalog();
    await screen.findByText('山间来信');
    await user.type(screen.getByLabelText('搜索作品'), '触发错误');
    await user.click(screen.getByRole('button', { name: '搜索' }));
    expect((await screen.findByRole('alert')).textContent).toContain('搜索加载失败');
    expect(screen.queryByText('没有找到与「触发错误」相关的作品')).toBeNull();
    expect(screen.getByRole('button', { name: '重试' })).toBeTruthy();
  });

  it('重试保留查询并恢复搜索结果', async () => {
    const user = userEvent.setup();
    renderCatalog();
    await screen.findByText('山间来信');
    await user.type(screen.getByLabelText('搜索作品'), '海雾');
    await user.click(screen.getByRole('button', { name: '搜索' }));
    expect(await screen.findByText('海雾纪事')).toBeTruthy();
    expect(screen.queryByText('山间来信')).toBeNull();
  });
});
