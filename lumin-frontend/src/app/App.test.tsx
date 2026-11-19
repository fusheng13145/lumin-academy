import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';

const work = {
  versionId: 'f05b9c8c-a440-4554-a711-9ebfc64e94b1',
  workId: '6d50c547-0e1c-493c-ae09-4a730c96807e',
  title: '山间来信',
  author: '林舟',
  grade: 'G',
  chapterId: null,
};
const envelope = (data: unknown) => new Response(JSON.stringify({
  code: 'OK', message: '', data, traceId: 'catalog-test',
}), { status: 200, headers: { 'Content-Type': 'application/json' } });

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (input) => {
    const path = String(input);
    if (path.includes('/me/works') || path.includes('/me/drafts')
      || path.includes('/me/publication-workflows') || path.includes('/review-cases')) {
      return envelope({ items: [], nextCursor: null });
    }
    return envelope([]);
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

async function openExamples() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: '浏览示例' }));
  return user;
}

function mainNavigation() {
  return screen.getByRole('navigation', { name: '主导航' });
}

describe('书城数据来源与状态', () => {
  it('请求未完成时只呈现加载状态，不冒充示例或空书城', () => {
    fetchMock.mockImplementation(() => new Promise(() => {}));
    render(<App />);
    expect(within(screen.getByRole('main')).getByText('正在加载作品')).toBeTruthy();
    expect(screen.queryByText('晚潮记')).toBeNull();
    expect(screen.queryByText('暂无作品')).toBeNull();
  });

  it('真实空列表保持空态，不自动回退示例', async () => {
    render(<App />);
    expect(await within(screen.getByRole('main')).findByText('暂无作品')).toBeTruthy();
    expect(screen.queryByText('晚潮记')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it.each(['网络中断', '空响应', 'API错误'])('%s只显示失败和重试，不显示空态或示例', async (failure) => {
    fetchMock.mockImplementation(async () => {
      if (failure === '网络中断') throw new TypeError('Failed to fetch');
      if (failure === '空响应') return new Response('', { status: 502 });
      return new Response(JSON.stringify({
        code: 'S5000', message: '内部诊断', data: null, traceId: 'failed-request',
      }), { status: 500 });
    });
    render(<App />);
    expect((await screen.findByRole('alert')).textContent).toContain('作品加载失败');
    expect(screen.getByRole('button', { name: '重试' })).toBeTruthy();
    expect(screen.queryByText('暂无作品')).toBeNull();
    expect(screen.queryByText('晚潮记')).toBeNull();
    expect(screen.queryByText(/Unexpected|内部诊断/)).toBeNull();
  });

  it('重试成功后清除错误并展示真实作品，点击进入同一作品详情', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('offline'));
    render(<App />);
    await screen.findByRole('alert');
    fetchMock.mockImplementation(async (input) => {
      if (input === '/api/v1/works') return envelope([work]);
      if (input === `/api/v1/works/${work.workId}`) {
        return envelope({ ...work, synopsis: '真实详情内容', categoryId: null, tags: [] });
      }
      if (input === `/api/v1/works/${work.workId}/chapters`) return envelope([]);
      throw new Error(`Unexpected request: ${String(input)}`);
    });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '重试' }));
    await user.click(await screen.findByRole('button', { name: /山间来信/ }));
    expect(await screen.findByText('真实详情内容')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByText('晚潮记')).toBeNull();
  });

  it('真实列表中的受限作品保留禁用状态，不能进入详情', async () => {
    fetchMock.mockImplementation(async () => envelope([{ ...work, grade: 'A' }]));
    render(<App />);
    const restricted = await screen.findByRole('button', { name: /山间来信/ }) as HTMLButtonElement;
    expect(restricted.disabled).toBe(true);
    await userEvent.setup().click(restricted);
    expect(screen.getByRole('heading', { name: '书城', level: 1 })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '开始阅读' })).toBeNull();
  });

  it('重试中不保留旧错误，也不允许重复发起重试', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('offline'));
    render(<App />);
    await screen.findByRole('alert');
    fetchMock.mockImplementation(() => new Promise(() => {}));
    await userEvent.setup().click(screen.getByRole('button', { name: '重试' }));
    expect(within(screen.getByRole('main')).getByText('正在加载作品')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('button', { name: '重试' })).toBeNull();
  });

  it('键盘重试期间及再次失败后焦点保留在书城标题', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('offline'));
    render(<App />);
    await screen.findByRole('alert');
    // 书城挂载后有两路在途请求（列表 + 运营精选位 UGC-039）：失败需拒绝全部
    const pendingRejects: Array<(reason: Error) => void> = [];
    fetchMock.mockImplementation(() => new Promise((_resolve, reject) => {
      pendingRejects.push(reject);
    }));
    const retry = screen.getByRole('button', { name: '重试' });
    retry.focus();
    await userEvent.setup().keyboard('{Enter}');
    const heading = screen.getByRole('heading', { name: '书城', level: 1 });
    expect(document.activeElement).toBe(heading);
    await act(async () => {
      pendingRejects.forEach((reject) => reject(new TypeError('offline')));
    });
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(document.activeElement).toBe(heading);
  });

  it('失败后清除过期的加载播报', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('offline'));
    render(<App />);
    await screen.findByRole('alert');
    expect(screen.getByRole('status').textContent).not.toContain('正在加载作品');
  });

  it('只有主动进入示例才显示合成作品，迟到的真实响应不能覆盖示例', async () => {
    let resolveRequest!: (response: Response) => void;
    fetchMock.mockImplementation(() => new Promise((resolve) => { resolveRequest = resolve; }));
    render(<App />);
    await openExamples();
    expect(screen.getByRole('heading', { level: 1, name: '示例书城' })).toBeTruthy();
    expect(screen.getByRole('button', { name: /晚潮记/ })).toBeTruthy();
    await act(async () => resolveRequest(envelope([work])));
    expect(screen.queryByText('山间来信')).toBeNull();
    expect(screen.getByRole('button', { name: /晚潮记/ })).toBeTruthy();
  });

  it('退出示例重新请求真实书城，空结果不保留示例', async () => {
    render(<App />);
    const user = await openExamples();
    await user.click(screen.getByRole('button', { name: '返回书城' }));
    expect(await within(screen.getByRole('main')).findByText('暂无作品')).toBeTruthy();
    expect(screen.queryByText('晚潮记')).toBeNull();
    expect(screen.getByRole('heading', { name: '书城', level: 1 })).toBeTruthy();
  });

  it('示例详情返回仍为示例，保留阅读入口', async () => {
    render(<App />);
    const user = await openExamples();
    await user.click(screen.getByRole('button', { name: /晚潮记/ }));
    expect(screen.getByRole('button', { name: '开始阅读' })).toBeTruthy();
    await user.click(within(screen.getByRole('main')).getByRole('button', { name: '书城' }));
    expect(screen.getByRole('heading', { name: '示例书城', level: 1 })).toBeTruthy();
  });

  it('示例筛选仍可组合并清空，受限作品不可打开', async () => {
    render(<App />);
    const user = await openExamples();
    const restricted = screen.getByRole('button', { name: /机巧京城/ }) as HTMLButtonElement;
    expect(restricted.disabled).toBe(true);
    await user.click(restricted);
    expect(screen.getByRole('heading', { name: '示例书城', level: 1 })).toBeTruthy();
    await user.click(screen.getByText('筛选', { exact: true }));
    await user.selectOptions(screen.getByRole('combobox', { name: '按状态筛选' }), 'removed');
    expect(screen.queryByRole('button', { name: /晚潮记/ })).toBeNull();
    await user.selectOptions(screen.getByRole('combobox', { name: '按字数筛选' }), 'gt60');
    expect(screen.getByText('没有符合条件的作品')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '清除筛选' }));
    expect(screen.getByRole('button', { name: /晚潮记/ })).toBeTruthy();
  });
});

describe('全局导航', () => {
  it('桌面和移动端使用相同简洁命名，所有工作区仍可抵达', async () => {
    render(<App />);
    const user = userEvent.setup();
    const top = within(mainNavigation());
    const bottom = within(screen.getByRole('navigation', { name: '底部导航' }));
    for (const [name, heading] of [['创作', '创作'], ['审核', '审核'], ['运营', '运营与配置'], ['我的', '我的'], ['书城', '书城']]) {
      for (const navigation of [top, bottom]) {
        const button = navigation.getByRole('button', { name });
        await user.click(button);
        expect(screen.getByRole('heading', { level: 1, name: heading })).toBeTruthy();
        expect(button.getAttribute('aria-current')).toBe('page');
      }
    }
  });

  it('切换年龄带后不呈现上一年龄带的旧书城结果', async () => {
    fetchMock.mockImplementation(async (input, options) => {
      if (input !== '/api/v1/works') return envelope([]);
      const band = new Headers(options?.headers).get('X-Lumin-Demo-Subject');
      return envelope(band === 'READER_C2' ? [work] : []);
    });
    render(<App />);
    expect(await screen.findByRole('button', { name: /山间来信/ })).toBeTruthy();
    const user = userEvent.setup();
    await user.click(within(mainNavigation()).getByRole('button', { name: '我的' }));
    await user.selectOptions(screen.getByRole('combobox', { name: '选择年龄带' }), 'C0');
    await user.click(within(mainNavigation()).getByRole('button', { name: '书城' }));
    await waitFor(() => expect(within(screen.getByRole('main')).getByText('暂无作品')).toBeTruthy());
    expect(screen.queryByText('山间来信')).toBeNull();
  });
});
