import { render, screen, cleanup, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RealBookDetailView } from './RealBookDetailView';

const workId = '6d50c547-0e1c-493c-ae09-4a730c96807e';
const detail = {
  workId, versionId: 'v-1', title: '山间来信', author: '林舟',
  synopsis: '沿着山路寻找旧信。', categoryId: null, tags: [], grade: 'G',
};
const chapters = [{ id: 'ch-1', sequenceNo: 1, businessState: 'PUBLISHED' }];

const envelope = (data: unknown) => new Response(JSON.stringify({
  code: 'OK', message: '', data, traceId: 'sub-entry-test',
}), { status: 200, headers: { 'Content-Type': 'application/json' } });

const fetchMock = vi.fn<typeof fetch>();
let subscribed = false;

beforeEach(() => {
  window.location.hash = '';
  subscribed = false;
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input);
    const method = (init?.method as string) ?? 'GET';
    if (url === `/api/v1/works/${workId}`) return envelope(detail);
    if (url === `/api/v1/works/${workId}/chapters`) return envelope(chapters);
    if (url === `/api/v1/me/subscriptions/${workId}/status`) return envelope({
      workId, subscribed,
    });
    if (method === 'POST' && url === '/api/v1/me/subscriptions') {
      subscribed = true;
      return envelope({ workId, subscribed: true });
    }
    if (method === 'DELETE' && url === `/api/v1/me/subscriptions/${workId}`) {
      subscribed = false;
      return envelope({ workId, subscribed: false });
    }
    throw new Error(`Unexpected request: ${method} ${url}`);
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('详情页追更入口（UGC-036 收口）', () => {
  it('未订阅 → 「追更更新」按钮 → 点击订阅成功翻转为「已追更」', async () => {
    const user = userEvent.setup();
    render(<RealBookDetailView workId={workId} index={0} ageBand="C2"
      onBack={() => {}} onRead={() => {}} />);
    const button = await screen.findByRole('button', { name: '追更更新' });
    expect(button.getAttribute('aria-pressed')).toBe('false');
    await user.click(button);
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '已追更 · 点击退订' })
        .getAttribute('aria-pressed')).toBe('true');
    });
    // POST 走 /me/subscriptions
    expect(fetchMock.mock.calls.some(
      ([url, init]) => String(url) === '/api/v1/me/subscriptions'
        && init?.method === 'POST',
    )).toBe(true);
  });

  it('已订阅 → 「已追更 · 点击退订」→ 点击退订翻回', async () => {
    const user = userEvent.setup();
    subscribed = true;
    render(<RealBookDetailView workId={workId} index={0} ageBand="C2"
      onBack={() => {}} onRead={() => {}} />);
    const button = await screen.findByRole('button', { name: '已追更 · 点击退订' });
    await user.click(button);
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '追更更新' })).toBeTruthy();
    });
    expect(fetchMock.mock.calls.some(
      ([url, init]) => String(url) === `/api/v1/me/subscriptions/${workId}`
        && init?.method === 'DELETE',
    )).toBe(true);
  });

  it('订阅失败如实报错且状态不翻转（不伪称成功）', async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input);
      const method = (init?.method as string) ?? 'GET';
      if (url === `/api/v1/works/${workId}`) return envelope(detail);
      if (url === `/api/v1/works/${workId}/chapters`) return envelope(chapters);
      if (url === `/api/v1/me/subscriptions/${workId}/status`) {
        return envelope({ workId, subscribed: false });
      }
      if (method === 'POST' && url === '/api/v1/me/submissions') {
        return envelope({ workId, subscribed: true });
      }
      if (method === 'POST' && url === '/api/v1/me/subscriptions') {
        return new Response(JSON.stringify({
          code: 'D2001', message: '依赖不可用', data: null, traceId: 't',
        }), { status: 503, headers: { 'Content-Type': 'application/json' } });
      }
      throw new Error(`Unexpected request: ${method} ${url}`);
    });
    render(<RealBookDetailView workId={workId} index={0} ageBand="C2"
      onBack={() => {}} onRead={() => {}} />);
    await user.click(await screen.findByRole('button', { name: '追更更新' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    // 状态未翻转：按钮仍为未订阅态
    expect(screen.getByRole('button', { name: '追更更新' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '已追更 · 点击退订' })).toBeNull();
  });
});
