import { useState } from 'react';
import { act, cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RealBookDetailView } from './RealBookDetailView';
import type { AgeBand } from '../../types';

const firstId = '6d50c547-0e1c-493c-ae09-4a730c96807e';
const secondId = '31313e5b-cfe8-439a-99dd-d579e7c50e76';
const detail = {
  workId: firstId,
  versionId: 'f05b9c8c-a440-4554-a711-9ebfc64e94b1',
  title: '山间来信', author: '林舟', synopsis: '沿着山路寻找旧信。',
  categoryId: null, tags: [], grade: 'G',
};
const chapters = [
  { id: 'chapter-one', sequenceNo: 1, businessState: 'PUBLISHED' },
  { id: 'chapter-two', sequenceNo: 2, businessState: 'PUBLISHED' },
];
const envelope = (data: unknown) => new Response(JSON.stringify({
  code: 'OK', message: '', data, traceId: 'detail-test',
}), { status: 200, headers: { 'Content-Type': 'application/json' } });
const fetchMock = vi.fn<typeof fetch>();

function DetailHarness({ workId = firstId, ageBand = 'C2' }: { workId?: string; ageBand?: AgeBand }) {
  const [destination, setDestination] = useState<string | null>(null);
  if (destination) return <h1>{destination}</h1>;
  return <RealBookDetailView workId={workId} ageBand={ageBand} index={0}
    onBack={() => setDestination('书城列表')}
    onRead={(id, index) => setDestination(`阅读 ${id} 第 ${index + 1} 章`)} />;
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockImplementation(async (input) => {
    if (input === `/api/v1/works/${firstId}`) return envelope(detail);
    if (input === `/api/v1/works/${firstId}/chapters`) return envelope(chapters);
    throw new Error(`Unexpected request: ${String(input)}`);
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('真实作品详情', () => {
  it('加载期间仍可返回，不出现可用的阅读入口', async () => {
    fetchMock.mockImplementation(() => new Promise(() => {}));
    render(<DetailHarness />);
    expect(screen.getByRole('region').getAttribute('aria-busy')).toBe('true');
    expect(screen.queryByRole('button', { name: '开始阅读' })).toBeNull();
    await userEvent.setup().click(screen.getByRole('button', { name: '书城' }));
    expect(screen.getByRole('heading', { name: '书城列表' })).toBeTruthy();
  });

  it.each(['网络失败', '空响应', '未授权'])('%s显示可恢复失败，不泄漏诊断或推断不可读原因', async (failure) => {
    fetchMock.mockImplementation(async () => {
      if (failure === '网络失败') throw new TypeError('Failed to fetch');
      if (failure === '空响应') return new Response('', { status: 502 });
      return new Response(JSON.stringify({ code: 'B2004', message: '内部诊断内容', data: null, traceId: 'hidden' }), { status: 404 });
    });
    render(<DetailHarness />);
    expect((await screen.findByRole('alert')).textContent).toContain('作品加载失败');
    expect(screen.getByRole('button', { name: '重试' })).toBeTruthy();
    expect(screen.queryByText(/内部诊断内容|Unexpected|Failed to fetch|作品不可读/)).toBeNull();
    await userEvent.setup().click(screen.getByRole('button', { name: '书城' }));
    expect(screen.getByRole('heading', { name: '书城列表' })).toBeTruthy();
  });

  it('目录请求失败后重试整个详情，恢复正确章节入口', async () => {
    fetchMock.mockImplementation(async (input) => {
      if (input === `/api/v1/works/${firstId}`) return envelope(detail);
      throw new TypeError('offline');
    });
    render(<DetailHarness />);
    await screen.findByRole('alert');
    fetchMock.mockImplementation(async (input) => envelope(String(input).endsWith('/chapters') ? chapters : detail));
    await userEvent.setup().click(screen.getByRole('button', { name: '重试' }));
    await userEvent.setup().click(await screen.findByRole('button', { name: '第 2 章' }));
    expect(screen.getByRole('heading', { name: `阅读 ${firstId} 第 2 章` })).toBeTruthy();
  });

  it('重试时清除错误，加载完成不丢失标题焦点', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('offline'));
    render(<DetailHarness />);
    await screen.findByRole('alert');
    let resolveRequest!: (response: Response) => void;
    fetchMock.mockImplementationOnce(() => new Promise((resolve) => { resolveRequest = resolve; }));
    screen.getByRole('button', { name: '重试' }).focus();
    await userEvent.setup().keyboard('{Enter}');
    const title = screen.getByRole('heading', { level: 1 });
    expect(document.activeElement).toBe(title);
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('button', { name: '重试' })).toBeNull();
    await act(async () => resolveRequest(envelope(detail)));
    expect(await screen.findByRole('heading', { name: '山间来信' })).toBe(title);
    expect(document.activeElement).toBe(title);
  });

  it('空目录没有可执行的阅读入口', async () => {
    fetchMock.mockImplementation(async (input) => envelope(String(input).endsWith('/chapters') ? [] : detail));
    render(<DetailHarness />);
    expect(await screen.findByText('暂无章节')).toBeTruthy();
    const read = screen.getByRole('button', { name: '开始阅读' }) as HTMLButtonElement;
    expect(read.disabled).toBe(true);
    await userEvent.setup().click(read);
    expect(screen.getByRole('heading', { name: '山间来信' })).toBeTruthy();
  });

  it('切换作品立即隐藏旧内容，迟到请求不能覆盖新详情', async () => {
    const { rerender } = render(<DetailHarness />);
    await screen.findByRole('heading', { name: '山间来信' });
    let resolveOld!: (response: Response) => void;
    fetchMock.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }));
    rerender(<DetailHarness workId={secondId} />);
    expect(screen.queryByRole('heading', { name: '山间来信' })).toBeNull();
    expect(screen.queryByRole('button', { name: '开始阅读' })).toBeNull();
    rerender(<DetailHarness workId={firstId} />);
    await screen.findByRole('heading', { name: '山间来信' });
    await act(async () => resolveOld(envelope({ ...detail, workId: secondId, title: '旧请求结果' })));
    expect(screen.queryByText('旧请求结果')).toBeNull();
    expect(screen.getByRole('heading', { name: '山间来信' })).toBeTruthy();
  });

  it('切换年龄带立即清除已加载内容，拒绝响应不保留旧入口', async () => {
    const { rerender } = render(<DetailHarness />);
    await screen.findByRole('heading', { name: '山间来信' });
    let rejectRequest!: (error: Error) => void;
    fetchMock.mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectRequest = reject; }));
    rerender(<DetailHarness ageBand="C0" />);
    expect(screen.queryByRole('heading', { name: '山间来信' })).toBeNull();
    expect(screen.queryByRole('button', { name: '开始阅读' })).toBeNull();
    await act(async () => rejectRequest(new TypeError('offline')));
    expect(await screen.findByRole('alert')).toBeTruthy();
  });

  it('前一作品失败不污染后一作品，简介为空不渲染空段落', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('offline'));
    const { rerender } = render(<DetailHarness workId={secondId} />);
    await screen.findByText('作品加载失败');
    fetchMock.mockImplementation(async (input) => envelope(String(input).endsWith('/chapters') ? chapters : { ...detail, synopsis: null }));
    rerender(<DetailHarness />);
    expect(await screen.findByRole('heading', { name: '山间来信' })).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('heading', { name: '简介' })).toBeNull();
  });

  it('成功加载可浏览语义化目录并从首章开始阅读', async () => {
    render(<DetailHarness />);
    await screen.findByRole('heading', { name: '山间来信' });
    expect(screen.getByRole('list', { name: '章节目录' })).toBeTruthy();
    await userEvent.setup().click(screen.getByRole('button', { name: '开始阅读' }));
    expect(screen.getByRole('heading', { name: `阅读 ${firstId} 第 1 章` })).toBeTruthy();
  });

  it('受限作品的主入口和章节入口均不可执行', async () => {
    fetchMock.mockImplementation(async (input) => envelope(String(input).endsWith('/chapters') ? chapters : { ...detail, grade: 'A' }));
    render(<DetailHarness />);
    const read = await screen.findByRole('button', { name: '暂不可读' }) as HTMLButtonElement;
    expect(read.disabled).toBe(true);
    const chapter = screen.getByRole('button', { name: '第 2 章' }) as HTMLButtonElement;
    expect(chapter.disabled).toBe(true);
    await userEvent.setup().click(chapter);
    expect(screen.getByRole('heading', { name: '山间来信' })).toBeTruthy();
  });
});
