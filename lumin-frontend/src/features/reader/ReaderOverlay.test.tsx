import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReaderOverlay } from './ReaderOverlay';
import { BOOKS } from '../../mocks/mock';

const book = BOOKS[0];
const noop = (): void => {};

function renderReader(chapterIdx = 0) {
  const onChapterChange = vi.fn();
  const onClose = vi.fn();
  const announce = vi.fn();
  render(
    <ReaderOverlay
      book={book}
      chapterIdx={chapterIdx}
      ageBand="C2"
      uiTheme="light"
      onToggleUiTheme={noop}
      announce={announce}
      onChapterChange={onChapterChange}
      onClose={onClose}
    />,
  );
  return { onChapterChange, onClose, announce };
}

beforeEach(() => {
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
  document.documentElement.removeAttribute('data-read-theme');
});

describe('ReaderOverlay 阅读体验（UGC-018 逐项）', () => {
  it('前后章：下一章推进并播报；上一章在首章边界不越界', async () => {
    const user = userEvent.setup();
    const { onChapterChange, announce } = renderReader(0);
    await user.click(screen.getByRole('button', { name: '下一章' }));
    expect(onChapterChange).toHaveBeenCalledWith(1);
    expect(announce).toHaveBeenCalledWith(
      expect.stringContaining('已切到第 2 章'),
    );
    await user.click(screen.getByRole('button', { name: '上一章' }));
    // 已在首章：边界如实播报，不越界
    expect(announce).toHaveBeenCalledWith('已是第一章。');
  });

  it('末章边界：下一章播报「已是最后一章」且不推进', async () => {
    const user = userEvent.setup();
    const last = book.chapters.length - 1;
    const { onChapterChange, announce } = renderReader(last);
    await user.click(screen.getByRole('button', { name: '下一章' }));
    expect(announce).toHaveBeenCalledWith('已是最后一章。');
    expect(onChapterChange).not.toHaveBeenCalled();
  });

  it('目录：列出全部章节并可跳章', async () => {
    const user = userEvent.setup();
    const { onChapterChange } = renderReader(0);
    // 目录按钮在排版工具栏内（窄视口默认收起）——先唤出工具栏
    await user.click(screen.getByRole('button', { name: '点击唤出排版工具栏' }));
    await user.click(screen.getByRole('button', { name: /目录/ }));
    const toc = screen.getByLabelText('章节目录');
    expect(toc).toBeTruthy();
    const second = screen.getByRole('button', { name: `第 2 章 ${book.chapters[1].title}` });
    await user.click(second);
    expect(onChapterChange).toHaveBeenCalledWith(1);
    // 跳章后目录收起
    expect(screen.queryByLabelText('章节目录')).toBeNull();
  });

  it('长章：超长正文全部渲染不截断', () => {
    const longBody = Array.from(
      { length: 300 },
      (_, i) => `第 ${i + 1} 段：潮水照旧涨落，记录继续。`,
    ).join('\n');
    render(
      <ReaderOverlay
        book={book}
        chapterIdx={0}
        ageBand="C2"
        uiTheme="light"
        onToggleUiTheme={noop}
        announce={noop}
        onChapterChange={noop}
        onClose={noop}
        realBody={longBody}
        realTitle="长章"
      />,
    );
    const paper = screen.getByText('长章').closest('.reader-paper') as HTMLElement;
    const paragraphs = paper.querySelectorAll('p');
    expect(paragraphs.length).toBe(300);
  });

  it('字体与主题：排版工具栏即时生效（仅体验层）', async () => {
    const user = userEvent.setup();
    renderReader(0);
    // 唤出排版工具栏
    await user.click(screen.getByRole('button', { name: '点击唤出排版工具栏' }));
    // 字号 18 → 22
    const fontSize = screen.getByLabelText('字号') as HTMLInputElement;
    fireEvent.change(fontSize, { target: { value: '22' } });
    const paper = document.querySelector<HTMLElement>('.reader-paper');
    expect(paper?.style.fontSize).toBe('22px');
    // 主题 米白 → 护眼绿
    await user.click(screen.getByRole('button', { name: '护眼绿' }));
    expect(document.documentElement.getAttribute('data-read-theme')).toBe('green');
    expect(screen.getByRole('button', { name: '护眼绿' })
      .getAttribute('aria-pressed')).toBe('true');
  });

  it('Esc 收起键盘提示；返回按钮关闭阅读器', async () => {
    const user = userEvent.setup();
    const { onClose } = renderReader(0);
    // 打开工具栏 → Esc 收起（键盘提示为每会话一次的模块态，跨用例不复现）
    await user.click(screen.getByRole('button', { name: '点击唤出排版工具栏' }));
    const toolbar = screen.getByRole('group', { name: '阅读排版设置（仅体验层）' });
    expect(toolbar.getAttribute('aria-hidden')).toBeNull();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(toolbar.getAttribute('aria-hidden')).toBe('true');
    await user.click(screen.getByRole('button', { name: '返回' }));
    expect(onClose).toHaveBeenCalled();
  });
});
