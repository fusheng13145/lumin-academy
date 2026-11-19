import { useCallback, useEffect, useRef, useState } from 'react';
import { TopNav } from '../components/layout/TopNav';
import { BottomTab } from '../components/layout/BottomTab';
import { SiteFooter } from '../components/layout/SiteFooter';
import { ReaderOverlay } from '../features/reader/ReaderOverlay';
import { CatalogView } from '../features/reader/CatalogView';
import { BookDetailView } from '../features/reader/BookDetailView';
import { RealBookDetailView } from '../features/reader/RealBookDetailView';
import { RealReaderOverlay } from '../features/reader/RealReaderOverlay';
import { AuthorView } from '../features/author/AuthorView';
import { ReviewView } from '../features/reviewer/ReviewView';
import { OperatorView } from '../features/operator/OperatorView';
import { MineView } from '../features/account/MineView';
import { BOOKS } from '../mocks/mock';
import { WORKSPACES } from './navigation';
import { parseHash, toHash } from '../lib/route';
import type { AppRoute } from '../lib/route';
import {
  BAND_LABEL,
  bookBlockedHint,
  bookBlockedReason,
  firstReadableBook,
} from '../lib/demoPolicy';
import type { AgeBand, ViewId, WorkspaceId } from '../types';

interface ReadingState {
  bookId: string;
  chapterIdx: number;
}

// 启明书院 Web 前端根组件：状态驱动路由（工作区 + 视图）、全站唯一 live region（C-38）。
export function App() {
  const [active, setActive] = useState<WorkspaceId>('catalog');
  const [view, setView] = useState<ViewId>('R01');
  const [catalogSource, setCatalogSource] = useState<'live' | 'demo'>('live');
  const [detailId, setDetailId] = useState<string | null>(null);
  const [reading, setReading] = useState<ReadingState | null>(null);
  const [ageBand, setAgeBand] = useState<AgeBand>('C2');
  const [uiTheme, setUiTheme] = useState<'light' | 'night'>('light');
  // 读者导航默认隐藏审核/运营入口（UGC-017）；「我的」内开关或深链可达，
  // 服务端权限不因菜单隐藏而省略（各工作台端点 403 闸独立存在）。
  const [showStaffNav, setShowStaffNav] = useState(false);
  const navWorkspaces = WORKSPACES.filter(
    (workspace) => workspace.defaultNav || showStaffNav,
  );
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

  // —— 哈希路由（UGC-017）：hash 是唯一地址真相。——
  // 导航动作经 commitRoute 直接置状态并写 hash（routeKey 防重入）；
  // hashchange（后退/前进/深链/刷新）解析后统一回灌状态。
  const routeKeyRef = useRef('');

  const applyRoute = (route: AppRoute): void => {
    switch (route.kind) {
      case 'workspace':
        setActive(route.workspace);
        setView('R01');
        setDetailId(null);
        setReading(null);
        break;
      case 'detail':
        setActive('catalog');
        setView('R02');
        setDetailId(route.workId);
        setReading(null);
        break;
      case 'reader':
        setActive('catalog');
        setView('R03');
        setDetailId(null);
        setReading({ bookId: route.workId, chapterIdx: route.chapterIdx });
        break;
    }
  };

  const commitRoute = (route: AppRoute, mode: 'push' | 'replace' = 'push'): void => {
    const hash = toHash(route);
    routeKeyRef.current = hash;
    if (mode === 'replace') {
      // 替换式：章节切换等高频动作不制造历史项（后退一次回到详情）
      window.history.replaceState(null, '', hash);
    } else if (window.location.hash !== hash) {
      window.location.hash = hash; // 触发 hashchange（守卫后不重复应用）
    }
    applyRoute(route);
  };

  useEffect(() => {
    const onHashChange = () => {
      const route = parseHash(window.location.hash);
      if (route === null) {
        return; // 页内锚点等非路由 hash：不动作
      }
      const key = toHash(route);
      if (key === routeKeyRef.current) {
        return; // 导航动作已先行应用
      }
      routeKeyRef.current = key;
      applyRoute(route);
      if (route.kind === 'reader') {
        announce(`已打开第 ${route.chapterIdx + 1} 章`);
      } else if (route.kind === 'detail') {
        announce('已打开作品');
      } else {
        const label = WORKSPACES.find((workspace) => workspace.id === route.workspace)?.label;
        if (label) {
          announce(`已切换到${label}`);
        }
      }
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
    // applyRoute/announce 稳定读取最新 state 经 setter，无需入 deps
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 首次挂载：深链/刷新恢复（无 hash 保持默认书城）。
  // 恢复经微任务应用——effect 体内同步 setState 会触发级联渲染规则。
  useEffect(() => {
    const route = parseHash(window.location.hash);
    if (route === null) {
      return;
    }
    queueMicrotask(() => {
      routeKeyRef.current = toHash(route);
      applyRoute(route);
    });
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
  // 真实作品 UUID 不在演示夹具 id 集内：按 id 归属分流演示/真实两条阅读链。
  const isRealWork = (id: string | null): boolean =>
    id !== null && !BOOKS.some((book) => book.id === id);
  // 书城真实列表第 N 项的封面配色序号：保证同一作品每次渲染封面一致。
  const realCoverIndex = (id: string): number =>
    id.split('').reduce((sum, ch) => sum + ch.charCodeAt(0), 0);

  const select = (id: WorkspaceId): void => {
    commitRoute({ kind: 'workspace', workspace: id });
    const label = WORKSPACES.find((workspace) => workspace.id === id)?.label ?? id;
    announce(`已切换到${label}`);
  };

  const openDetail = (bookId: string): void => {
    const book = BOOKS.find((item) => item.id === bookId);
    if (!book) {
      // 真实作品：门禁权威在后端（未授权 404 由详情页如实呈现）。
      commitRoute({ kind: 'detail', workId: bookId });
      announce('已打开作品');
      return;
    }
    if (bookBlockedReason(book, ageBand) !== null) {
      announce(`《${book.title}》${bookBlockedHint(book, ageBand)}`);
      return;
    }
    commitRoute({ kind: 'detail', workId: bookId });
    announce(`已打开《${book.title}》`);
  };

  const backToCatalog = (): void => {
    commitRoute({ kind: 'workspace', workspace: 'catalog' });
    announce('已返回书城。');
  };

  const openReader = (bookId: string, chapterIdx: number): void => {
    const book = BOOKS.find((item) => item.id === bookId);
    if (!book) {
      // 真实作品：正文可见性由后端闸门裁决。
      readerTriggerRef.current = document.activeElement as HTMLElement | null;
      commitRoute({ kind: 'reader', workId: bookId, chapterIdx });
      announce(`已打开第 ${chapterIdx + 1} 章`);
      return;
    }
    // 阅读入口兜底：与 R01 / R02 共用 demoPolicy 判定。
    if (bookBlockedReason(book, ageBand) !== null) {
      announce(`《${book.title}》${bookBlockedHint(book, ageBand)}`);
      return;
    }
    readerTriggerRef.current = document.activeElement as HTMLElement | null;
    commitRoute({ kind: 'reader', workId: bookId, chapterIdx });
    announce(`已打开《${book.title}》第 ${chapterIdx + 1} 章`);
  };

  const closeReader = (): void => {
    // 后退语义优先：有历史上一页（详情/书城）则交还给浏览器，否则显式回退。
    if (reading) {
      const fromHash = toHash({ kind: 'reader', workId: reading.bookId,
        chapterIdx: reading.chapterIdx });
      if (window.location.hash === fromHash && window.history.length > 1) {
        window.history.back();
        announce('已退出阅读。');
        return;
      }
    }
    commitRoute(detailId
      ? { kind: 'detail', workId: detailId }
      : { kind: 'workspace', workspace: 'catalog' });
    announce('已退出阅读。');
  };

  const changeChapter = useCallback((chapterIdx: number): void => {
    setReading((previous) => (previous ? { ...previous, chapterIdx } : previous));
  }, []);

  // 阅读态 → 地址同步（替换式）：章节切换/演示年龄带兜底换书都不制造历史项；
  // 后退一次即回详情。routeKey 同步防后续 hashchange 重复应用。
  useEffect(() => {
    if (reading && view === 'R03') {
      const hash = toHash({ kind: 'reader', workId: reading.bookId,
        chapterIdx: reading.chapterIdx });
      routeKeyRef.current = hash;
      window.history.replaceState(null, '', hash);
    }
  }, [reading, view]);

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
          switched = `已切换到《${fallback.title}》`;
        }
      }
    }

    announce(switched + `年龄带已切换为 ${BAND_LABEL[band]}`);
  };

  const toggleUiTheme = (): void => {
    setUiTheme((previous) => (previous === 'night' ? 'light' : 'night'));
  };

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">跳到正文</a>

      <TopNav active={active} onSelect={select} workspaces={navWorkspaces} />

      <main id="main" className="app-main" tabIndex={-1}>
        {active === 'catalog' && view === 'R01' && (
          <CatalogView
            source={catalogSource}
            onSourceChange={setCatalogSource}
            ageBand={ageBand}
            announce={announce}
            onOpenDetail={openDetail}
          />
        )}
        {/* R03 全屏覆盖层之下保留 R02 书详情，使「关闭回焦」的触发按钮不被卸载。 */}
        {active === 'catalog' && view !== 'R01' && detailId && isRealWork(detailId) && (
          <RealBookDetailView
            key={detailId}
            workId={detailId}
            index={realCoverIndex(detailId)}
            ageBand={ageBand}
            onBack={backToCatalog}
            onRead={openReader}
          />
        )}
        {active === 'catalog' && view !== 'R01' && detailBook && (
          <BookDetailView
            state={{ status: 'ready', book: detailBook }}
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
            showStaffNav={showStaffNav}
            onShowStaffNavChange={() => setShowStaffNav((previous) => !previous)}
          />
        )}
      </main>

      <SiteFooter onGoMine={() => select('me')} />
      <BottomTab active={active} onSelect={select} workspaces={navWorkspaces} />

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

      {reading && !readingBook && (
        <RealReaderOverlay
          workId={reading.bookId}
          chapterIdx={reading.chapterIdx}
          index={realCoverIndex(reading.bookId)}
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
