import { useCallback, useEffect, useRef, useState } from 'react';
import { TopNav } from './components/TopNav';
import { BottomTab } from './components/BottomTab';
import { SiteFooter } from './components/SiteFooter';
import { ReaderOverlay } from './components/ReaderOverlay';
import { CatalogView } from './views/CatalogView';
import { BookDetailView } from './views/BookDetailView';
import { AuthorView } from './views/AuthorView';
import { ReviewView } from './views/ReviewView';
import { OperatorView } from './views/OperatorView';
import { MineView } from './views/MineView';
import { BOOKS, WORKSPACES } from './data/mock';
import {
  BAND_LABEL,
  bookBlockedHint,
  bookBlockedReason,
  firstReadableBook,
} from './lib/demoPolicy';
import type { AgeBand, ViewId, WorkspaceId } from './types';

interface ReadingState {
  bookId: string;
  chapterIdx: number;
}

// 启明书院 Web 前端根组件：状态驱动路由（工作区 + 视图）、全站唯一 live region（C-38）。
export function App() {
  const [active, setActive] = useState<WorkspaceId>('catalog');
  const [view, setView] = useState<ViewId>('R01');
  const [detailId, setDetailId] = useState<string | null>(null);
  const [reading, setReading] = useState<ReadingState | null>(null);
  const [ageBand, setAgeBand] = useState<AgeBand>('C2');
  const [uiTheme, setUiTheme] = useState<'light' | 'night'>('light');
  const liveRef = useRef<HTMLDivElement>(null);
  const lastMessage = useRef('');
  // 记录打开阅读器的触发按钮，关闭时把焦点归还给它（而非一律落到 h1）。
  const readerTriggerRef = useRef<HTMLElement | null>(null);

  const announce = useCallback((message: string): void => {
    if (lastMessage.current === message) {
      return;
    }
    lastMessage.current = message;
    if (liveRef.current) {
      liveRef.current.textContent = message;
    }
  }, []);

  useEffect(() => {
    document.documentElement.setAttribute('data-ui-theme', uiTheme === 'night' ? 'night' : 'light');
  }, [uiTheme]);

  // 视图切换后把焦点交给该视图的 h1；关闭阅读器时优先归还给触发按钮。
  // 阅读器打开时不抢焦点（由 ReaderOverlay 自行把焦点移入弹层）。
  useEffect(() => {
    if (reading) {
      return;
    }
    const trigger = readerTriggerRef.current;
    readerTriggerRef.current = null;
    if (trigger?.isConnected && trigger !== document.body) {
      trigger.focus({ preventScroll: true });
      return;
    }
    const heading = document.getElementById('main')?.querySelector<HTMLElement>('h1');
    if (heading) {
      heading.setAttribute('tabindex', '-1');
      heading.focus({ preventScroll: true });
    }
  }, [active, view, reading]);

  const detailBook = detailId ? BOOKS.find((book) => book.id === detailId) ?? null : null;
  const readingBook = reading ? BOOKS.find((book) => book.id === reading.bookId) ?? null : null;

  const select = (id: WorkspaceId): void => {
    setActive(id);
    setView('R01');
    setDetailId(null);
    setReading(null);
    const label = WORKSPACES.find((workspace) => workspace.id === id)?.label ?? id;
    announce(`已切换到${label}。此为 demo 视图切换，非登录态。`);
  };

  const openDetail = (bookId: string): void => {
    const book = BOOKS.find((item) => item.id === bookId);
    if (!book) {
      return;
    }
    if (bookBlockedReason(book, ageBand) !== null) {
      announce(`《${book.title}》${bookBlockedHint(book, ageBand)}`);
      return;
    }
    setDetailId(bookId);
    setView('R02');
    announce(`已打开《${book.title}》书籍详情。面包屑可返回书城。`);
  };

  const backToCatalog = (): void => {
    setDetailId(null);
    setView('R01');
    announce('已返回书城。');
  };

  const openReader = (bookId: string, chapterIdx: number): void => {
    const book = BOOKS.find((item) => item.id === bookId);
    if (!book) {
      return;
    }
    // 阅读入口兜底：与 R01 / R02 共用 demoPolicy 判定。
    if (bookBlockedReason(book, ageBand) !== null) {
      announce(`《${book.title}》${bookBlockedHint(book, ageBand)}`);
      return;
    }
    readerTriggerRef.current = document.activeElement as HTMLElement | null;
    setReading({ bookId, chapterIdx });
    setView('R03');
    announce(`已打开《${book.title}》第 ${chapterIdx + 1} 章。试试使用键盘左右键切章。`);
  };

  const closeReader = (): void => {
    setReading(null);
    setView(detailId ? 'R02' : 'R01');
    announce('已退出阅读。');
  };

  const changeChapter = useCallback((chapterIdx: number): void => {
    setReading((previous) => (previous ? { ...previous, chapterIdx } : previous));
  }, []);

  const changeAgeBand = (band: AgeBand): void => {
    setAgeBand(band);
    let switched = '';

    if (view === 'R02' && detailBook && bookBlockedReason(detailBook, band) !== null) {
      const fallback = firstReadableBook(BOOKS, band);
      if (fallback) {
        setDetailId(fallback.id);
        switched = `当前详情《${detailBook.title}》已不适配新演示年龄带，已切换到《${fallback.title}》。`;
      }
    }

    if (reading) {
      const current = BOOKS.find((book) => book.id === reading.bookId);
      if (current && bookBlockedReason(current, band) !== null) {
        const fallback = firstReadableBook(BOOKS, band);
        if (fallback) {
          setReading({ bookId: fallback.id, chapterIdx: 0 });
          switched = `当前阅读的《${current.title}》已不适配新演示年龄带，已切换到《${fallback.title}》第一章。`;
        }
      }
    }

    announce(
      switched +
        `演示年龄带已切换为 ${BAND_LABEL[band]}，书城灰显已更新。仅演示边界，不代表真实年龄政策。`
    );
  };

  const toggleUiTheme = (): void => {
    setUiTheme((previous) => (previous === 'night' ? 'light' : 'night'));
  };

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">跳到正文</a>

      <TopNav active={active} onSelect={select} />

      <main id="main" className="app-main" tabIndex={-1}>
        {active === 'catalog' && view === 'R01' && (
          <CatalogView ageBand={ageBand} announce={announce} onOpenDetail={openDetail} />
        )}
        {/* R03 全屏覆盖层之下保留 R02 书详情，使「关闭回焦」的触发按钮不被卸载。 */}
        {active === 'catalog' && view !== 'R01' && detailBook && (
          <BookDetailView
            book={detailBook}
            ageBand={ageBand}
            onBack={backToCatalog}
            onRead={openReader}
          />
        )}
        {active === 'author' && <AuthorView />}
        {active === 'reviewer' && <ReviewView />}
        {active === 'operator' && <OperatorView />}
        {active === 'me' && (
          <MineView
            uiTheme={uiTheme}
            onToggleUiTheme={toggleUiTheme}
            ageBand={ageBand}
            onAgeBandChange={changeAgeBand}
          />
        )}
      </main>

      <SiteFooter onGoMine={() => select('me')} />
      <BottomTab active={active} onSelect={select} />

      {reading && readingBook && (
        <ReaderOverlay
          book={readingBook}
          chapterIdx={reading.chapterIdx}
          ageBand={ageBand}
          uiTheme={uiTheme}
          onToggleUiTheme={toggleUiTheme}
          announce={announce}
          onChapterChange={changeChapter}
          onClose={closeReader}
        />
      )}

      {/* 单一 aria-live 播报区（C-38 修复：全站仅此一处） */}
      <div
        id="live"
        className="sr-only"
        role="status"
        aria-live="polite"
        aria-atomic="true"
        ref={liveRef}
      />
    </div>
  );
}
