import { render, screen, cleanup, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../app/App';

const work = {
  versionId: 'v-1',
  workId: '6d50c547-0e1c-493c-ae09-4a730c96807e',
  title: '山间来信',
  author: '林舟',
  grade: 'G',
  chapterId: 'c-1',
};

const envelope = (data: unknown) => new Response(JSON.stringify({
  code: 'OK', message: '', data, traceId: 'route-test',
}), { status: 200, headers: { 'Content-Type': 'application/json' } });

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  window.location.hash = '';
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockImplementation(async (input) => {
    const url = String(input);
    if (url === '/api/v1/works') return envelope([work]);
    if (url === '/api/v1/works/featured') return envelope([]);
    if (url === `/api/v1/works/${work.workId}`) {
      return envelope({ ...work, synopsis: '真实详情内容', categoryId: null, tags: [] });
    }
    if (url === `/api/v1/works/${work.workId}/chapters`) return envelope([]);
    throw new Error(`Unexpected request: ${url}`);
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
});

describe('哈希路由（UGC-017）', () => {
  it('深链直达工作区：#/me 渲染我的（含入口开关）', async () => {
    window.location.hash = '#/me';
    render(<App />);
    expect(await screen.findByRole('heading', { name: '我的', level: 1 })).toBeTruthy();
    expect(screen.getByRole('switch', { name: '显示审核/运营入口' })).toBeTruthy();
  });

  it('读者默认导航隐藏审核/运营，深链仍可达（服务端权限独立存在）', async () => {
    render(<App />);
    await screen.findByRole('heading', { name: '书城', level: 1 });
    expect(screen.queryByRole('button', { name: '审核' })).toBeNull();
    expect(screen.queryByRole('button', { name: '运营' })).toBeNull();
    // 深链直达隐藏工作区
    window.location.hash = '#/reviewer';
    expect(await screen.findByRole('heading', { name: '审核', level: 1 })).toBeTruthy();
  });

  it('「我的」开关打开后导航出现审核/运营入口', async () => {
    const user = userEvent.setup();
    window.location.hash = '#/me';
    render(<App />);
    const toggle = await screen.findByRole('switch', { name: '显示审核/运营入口' });
    await user.click(toggle);
    // 顶导 + 底部标签同现审核/运营
    expect(screen.getAllByRole('button', { name: '审核' }).length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByRole('button', { name: '运营' }).length).toBeGreaterThanOrEqual(2);
    // 开关仍留在「我的」（可再次关闭）
    expect(screen.getByRole('switch', { name: '显示审核/运营入口' })).toBeTruthy();
  });

  it('打开作品详情写入真实地址，后退返回书城', async () => {
    const user = userEvent.setup();
    render(<App />);
    const card = await screen.findByRole('button', { name: /山间来信/ });
    await user.click(card);
    await waitFor(() => {
      expect(window.location.hash).toBe(`#/work/${work.workId}`);
    });
    expect(await screen.findByText('真实详情内容')).toBeTruthy();
    window.history.back();
    // 初始入口无 hash——后退回到无 hash 状态或 '#/' 等价
    await waitFor(() => {
      expect(['', '#/']).toContain(window.location.hash);
    });
    expect(await screen.findByRole('heading', { name: '书城', level: 1 })).toBeTruthy();
  });

  it('非法页内锚点不影响当前视图', async () => {
    render(<App />);
    await screen.findByRole('heading', { name: '书城', level: 1 });
    window.location.hash = '#main';
    await waitFor(() => {
      expect(window.location.hash).toBe('#main');
    });
    // 非路由 hash：视图保持书城，不崩溃不跳转
    expect(screen.getByRole('heading', { name: '书城', level: 1 })).toBeTruthy();
  });
});
