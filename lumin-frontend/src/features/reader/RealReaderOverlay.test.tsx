import { render, screen, cleanup, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RealReaderOverlay } from './RealReaderOverlay';
import type { AgeBand } from '../../types';

const workId = '6d50c547-0e1c-493c-ae09-4a730c96807e';
const detail = {
  workId, versionId: 'v-1', title: '山间来信', author: '林舟',
  synopsis: '沿着山路寻找旧信。', categoryId: null, tags: [], grade: 'G',
};
const chapters = [
  { id: 'ch-1', sequenceNo: 1, businessState: 'PUBLISHED' },
  { id: 'ch-2', sequenceNo: 2, businessState: 'PUBLISHED' },
];
const content = (versionId = 'v-1') => ({
  chapterId: 'ch-1', versionId, title: '第一章 夜航',
  body: '正文第一段。\n正文第二段。', grade: 'G',
});
const progress = (versionId: string) => ({
  chapterId: 'ch-1', versionId, positionBasisPoints: 5000,
  readSeconds: 60, clientUpdatedAt: '2026-11-20T10:00:00Z', etag: '1',
});

const envelope = (data: unknown) => new Response(JSON.stringify({
  code: 'OK', message: '', data, traceId: 'reader-test',
}), { status: 200, headers: { 'Content-Type': 'application/json' } });

const fetchMock = vi.fn<typeof fetch>();
const announce = vi.fn<(message: string) => void>();
const noop = (): void => {};

beforeEach(() => {
  window.location.hash = '';
  announce.mockClear();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  window.location.hash = '';
  // jsdom 无 matchMedia：ReaderOverlay 挂载即探测视口宽度（UGC-018 验证暴露）
  vi.stubGlobal('matchMedia', vi.fn().mockImplementation((query: string) => ({
    matches: false, media: query, onchange: null,
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(), dispatchEvent: vi.fn(),
  })));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function defaultMocks(): void {
  fetchMock.mockImplementation(async (input) => {
    const url = String(input);
    if (url === `/api/v1/works/${workId}`) return envelope(detail);
    if (url === `/api/v1/works/${workId}/chapters`) return envelope(chapters);
    if (url === '/api/v1/chapters/ch-1/content') return envelope(content());
    if (url === '/api/v1/me/reading-progress/ch-1') return envelope(progress('v-1'));
    throw new Error(`Unexpected request: ${url}`);
  });
}

function renderReader(): void {
  render(
    <RealReaderOverlay
      workId={workId}
      chapterIdx={0}
      index={0}
      ageBand={'C2' as AgeBand}
      uiTheme="light"
      onToggleUiTheme={noop}
      announce={announce}
      onChapterChange={noop}
      onClose={noop}
    />,
  );
}

describe('RealReaderOverlay 阅读体验（UGC-018 逐项）', () => {
  it('弱网：正文未返回时如实呈现加载态，不冒充内容', async () => {
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === `/api/v1/works/${workId}`) return envelope(detail);
      if (url === `/api/v1/works/${workId}/chapters`) return envelope(chapters);
      if (url === '/api/v1/chapters/ch-1/content') return new Promise(() => {});
      throw new Error(`Unexpected request: ${url}`);
    });
    renderReader();
    expect(await screen.findByText('正在获取正文')).toBeTruthy();
    expect(screen.queryByText('正文第一段。')).toBeNull();
    // 阅读器骨架仍在（弱网部分降级而非白屏）：标题栏与排版入口存在
    expect(screen.getByRole('button', { name: '点击唤出排版工具栏' })).toBeTruthy();
    expect(screen.getByLabelText('阅读：山间来信')).toBeTruthy();
  });

  it('长章：数百段落全部渲染，不截断不崩溃', async () => {
    const longBody = Array.from(
      { length: 400 },
      (_, i) => `第 ${i + 1} 段：栈桥尽头的灯一盏盏亮起，记录簿翻过新的一页。`,
    ).join('\n');
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === `/api/v1/works/${workId}`) return envelope(detail);
      if (url === `/api/v1/works/${workId}/chapters`) return envelope(chapters);
      if (url === '/api/v1/chapters/ch-1/content') {
        return envelope({ ...content('v-1'), body: longBody });
      }
      if (url === '/api/v1/me/reading-progress/ch-1') throw new TypeError('offline');
      throw new Error(`Unexpected request: ${url}`);
    });
    renderReader();
    const paper = await screen.findByText('第一章 夜航')
      .then((el) => el.closest('.reader-paper') as HTMLElement);
    await waitFor(() => {
      expect(paper.querySelectorAll('p').length).toBe(400);
    });
  });

  it('版本变化：进度绑定旧版本时如实播报，不跨版本套用位置', async () => {
    // 进度绑定旧版本（v-old）而当前正文是 v-1 → 如实播报版本变化
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === `/api/v1/works/${workId}`) return envelope(detail);
      if (url === `/api/v1/works/${workId}/chapters`) return envelope(chapters);
      if (url === '/api/v1/chapters/ch-1/content') return envelope(content('v-1'));
      if (url === '/api/v1/me/reading-progress/ch-1') return envelope(progress('v-old'));
      throw new Error(`Unexpected request: ${url}`);
    });
    renderReader();
    await screen.findByText('第一章 夜航');
    await waitFor(() => {
      expect(announce).toHaveBeenCalledWith(
        '该章节已更新至新版本，之前保存的阅读位置属于旧版本。',
      );
    });
  });

  it('同版本：不误报版本变化（恢复静默进行）', async () => {
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === `/api/v1/works/${workId}`) return envelope(detail);
      if (url === `/api/v1/works/${workId}/chapters`) return envelope(chapters);
      if (url === '/api/v1/chapters/ch-1/content') return envelope(content('v-1'));
      if (url === '/api/v1/me/reading-progress/ch-1') return envelope(progress('v-1'));
      throw new Error(`Unexpected request: ${url}`);
    });
    renderReader();
    await screen.findByText('第一章 夜航');
    await waitFor(() => {
      // 内容与进度都已返回
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/me/reading-progress/ch-1', expect.anything(),
      );
    });
    expect(announce).not.toHaveBeenCalledWith(
      expect.stringContaining('已更新至新版本'),
    );
  });

  it('真实路径无示例标语（演示夹具文案不泄漏进阅读器）', async () => {
    defaultMocks();
    renderReader();
    await screen.findByText('第一章 夜航');
    expect(screen.queryByText(/（示例）/)).toBeNull();
    expect(screen.queryByText(/本章字数：/)).toBeNull();
  });
});
