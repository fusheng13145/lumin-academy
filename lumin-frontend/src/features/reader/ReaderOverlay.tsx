import { useCallback, useEffect, useRef, useState } from 'react';
import { Icon } from '../../components/icons/Icon';
import { READ_THEMES } from '../../mocks/mock';
import { bookBlockedHint, bookBlockedReason } from '../../lib/demoPolicy';
import { scrollRatioToBasisPoints } from '../../lib/readingProgressSync';
import type { AgeBand, Book, ReadThemeId } from '../../types';

interface ReaderOverlayProps {
  book: Book;
  chapterIdx: number;
  ageBand: AgeBand;
  uiTheme: 'light' | 'night';
  onToggleUiTheme: () => void;
  announce: (message: string) => void;
  onChapterChange: (chapterIdx: number) => void;
  onClose: () => void;
  /** 真实数据路径：已发布正文纯文本（null = 演示夹具路径或加载中）。 */
  realBody?: string | null;
  /** 真实数据路径：当前章节标题（来自不可变版本）。 */
  realTitle?: string | null;
  /** 真实数据路径：正文拉取失败提示。 */
  realError?: string | null;
  /** 真实数据路径：正文在途（弱网/慢加载）——强制真实路径的加载态，演示夹具不冒充正文。 */
  realLoading?: boolean;
  /** 真实数据路径：滚动位置回调（万分比 0–10000；UGC-016 进度保存链）。 */
  onScrollRatio?: (positionBasisPoints: number) => void;
  /** 真实数据路径：同版本恢复位置（null = 不恢复；版本不同不跨版本套用）。 */
  initialScrollRatio?: number | null;
}

// 单章原创正文段落池：按章号确定性取三段，不读系统时钟。
const BODY_POOL: string[] = [
  '天色尚早，窗纸先亮了一层。她把昨夜抄好的页码摊在桌上，又用镇纸压住被风掀起的一角。屋外有人推车经过，木轮碾过湿石的声响慢而稳，像在替这段尚未落笔的记述数拍子。',
  '记录簿的边角已经卷起，前任书写人留下的墨色深浅不一。她沿着每一道笔画往下读，忽然发现被略去的那一行并不在末尾，而是藏在两次寻常交接之间——最不起眼的地方，往往最经得起反复翻看。',
  '午后起了风，院里的影子被拉得很长。来人没有进门，只把一只封好的纸袋放在门槛上，说是等潮退了再拆。她点头应下，转身把这件事照原样记进本子，既不添一句猜测，也不省一个日期。',
  '灯芯爆了一下，屋里短暂地更亮。她想起有人说过，把事情写清楚比把事情说漂亮要难得多。于是删去两行形容，只保留时间、地点与在场的人，让纸面重新安静下来，像退潮后露出的那片平沙。',
  '第二日清早，雾还没散。栈桥尽头传来缆绳收紧的动静，值夜的人正按旧例核对一遍数目。她站在稍远处看，没有上前打扰；有些秩序不必被解释，只要每天有人肯照着做一遍，就足以让后来者安心。',
  '旧信被重新折好，收进原先的封套。纸上的折痕比字迹更旧，说明它曾被反复取出又放回。她忽然明白，所谓交代并不是给出一个结论，而是把来龙去脉留到足够完整，让下一个人不必从猜测开始。',
  '雨停之后，石阶上留着一行浅浅的水印。她顺着水印走到拐角，看见昨日那盏没点亮的灯已经被人擦干净。没有留名，也没有附言；这种不声张的照看，恰好是这些年里最不曾间断的一件事。',
  '夜班将尽，本子合上前又添了最后一行：今日无异常。写完她停了片刻，把笔搁在槽里，听远处第一班船鸣笛。潮水照旧涨落，而记录之所以要继续，正是为了让每个平常日子都有可以回看的位置。',
];

let kbTipShown = false;

function collectFocusables(root: HTMLElement | null): HTMLElement[] {
  if (!root) {
    return [];
  }
  const nodes = root.querySelectorAll<HTMLElement>(
    'button:not([disabled]), input:not([disabled]), select:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'
  );
  return Array.from(nodes).filter((element) => {
    const style = window.getComputedStyle(element);
    return (
      style.display !== 'none' &&
      style.visibility !== 'hidden' &&
      element.getClientRects().length > 0
    );
  });
}

// R03 阅读器：保持全屏覆盖层形态；双形态单 DOM 工具栏、键盘切章、章节元信息、
// 焦点进入 / Tab 不逸出 / 关闭回焦，均在此组件内完成。
export function ReaderOverlay({
  book,
  chapterIdx,
  ageBand,
  uiTheme,
  onToggleUiTheme,
  announce,
  onChapterChange,
  onClose,
  realBody = null,
  realTitle = null,
  realError = null,
  realLoading = false,
  onScrollRatio,
  initialScrollRatio = null,
}: ReaderOverlayProps) {
  const [toolbarOpen, setToolbarOpen] = useState(false);
  const [wide, setWide] = useState(() => window.matchMedia('(min-width: 1024px)').matches);
  const [fontSize, setFontSize] = useState(18);
  const [lineHeight, setLineHeight] = useState(1.9);
  const [margin, setMargin] = useState(1);
  const [theme, setTheme] = useState<ReadThemeId>('rice');
  const [showNotes, setShowNotes] = useState(true);
  const [tocOpen, setTocOpen] = useState(false);
  const [tipVisible, setTipVisible] = useState(!kbTipShown);

  const rootRef = useRef<HTMLDivElement>(null);
  const toolbarRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const shellRef = useRef<HTMLDivElement | null>(null);
  // 回调经 ref 透传：滚动高频触发，不因父组件闭包变化重渲染。
  const onScrollRatioRef = useRef(onScrollRatio);
  useEffect(() => {
    onScrollRatioRef.current = onScrollRatio;
  }, [onScrollRatio]);

  // 滚动位置 → 万分比（reader-shell 是唯一滚动容器）；恢复仅同版本一次性生效。
  const handleReaderScroll = useCallback(() => {
    const el = shellRef.current;
    if (!el || !onScrollRatioRef.current) {
      return;
    }
    const scrollable = el.scrollHeight - el.clientHeight;
    const ratio = scrollable > 0 ? el.scrollTop / scrollable : 0;
    onScrollRatioRef.current(scrollRatioToBasisPoints(ratio));
  }, []);

  const idx = Math.min(Math.max(chapterIdx, 0), book.chapters.length - 1);
  const chapter = book.chapters[idx];
  const marginPx = margin === 0 ? '8%' : margin === 1 ? '12%' : '18%';
  const isReal = realBody !== null || realError !== null || realLoading;
  const paragraphs = isReal
    ? (realBody ?? '').split(/\n+/).map((text) => text.trim()).filter((text) => text !== '')
    : [
        BODY_POOL[idx % BODY_POOL.length],
        BODY_POOL[(idx + 3) % BODY_POOL.length],
        BODY_POOL[(idx + 5) % BODY_POOL.length],
      ];

  // 同版本恢复位置（UGC-016）：正文渲染完成后一次性滚动到进度位置；
  // 版本不同时父组件不传值，位置不跨版本套用。
  useEffect(() => {
    if (initialScrollRatio === null) {
      return;
    }
    const el = shellRef.current;
    if (!el) {
      return;
    }
    const frame = requestAnimationFrame(() => {
      const scrollable = el.scrollHeight - el.clientHeight;
      el.scrollTop = Math.min(1, Math.max(0, initialScrollRatio)) * scrollable;
    });
    return () => cancelAnimationFrame(frame);
  }, [initialScrollRatio, realBody, idx]);

  // 阅读主题写入根属性：从渲染期副作用移入 useEffect（React 19 StrictMode 安全）。
  useEffect(() => {
    document.documentElement.setAttribute('data-read-theme', theme);
    return () => document.documentElement.removeAttribute('data-read-theme');
  }, [theme]);

  // 断点状态：仅用于决定 aria-hidden 与唤出逻辑；工具栏始终是同一 DOM。
  useEffect(() => {
    const query = window.matchMedia('(min-width: 1024px)');
    const update = (): void => setWide(query.matches);
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);

  // 打开时焦点进入，关闭时归还触发元素。
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const first = collectFocusables(rootRef.current)[0];
    if (first) {
      first.focus({ preventScroll: true });
    }
    return () => {
      if (previous && typeof previous.focus === 'function') {
        previous.focus({ preventScroll: true });
      }
    };
  }, []);

  // 一次性键盘提示：仅首次进入 R03 显示；Esc 或 5 秒后消失，不阻塞阅读。
  // 「只显示一次」与「5 秒计时」拆成两个 effect：React 19 StrictMode 会双调用 effect，
  // 合并写法会在清理后因 kbTipShown 已为 true 而不再重新计时，导致提示无法自动收起。
  useEffect(() => {
    kbTipShown = true;
  }, []);

  useEffect(() => {
    if (!tipVisible) {
      return;
    }
    const timer = window.setTimeout(() => setTipVisible(false), 5000);
    return () => window.clearTimeout(timer);
  }, [tipVisible]);

  const closeToolbar = useCallback((): void => {
    setToolbarOpen(false);
    announce('排版工具栏已收起。');
    triggerRef.current?.focus({ preventScroll: true });
  }, [announce]);

  const openToolbar = useCallback((): void => {
    setToolbarOpen(true);
    announce('已唤出排版工具栏：字号、行距、页边距与阅读主题。');
    window.requestAnimationFrame(() => {
      const first = collectFocusables(toolbarRef.current)[0];
      if (first) {
        first.focus({ preventScroll: true });
      }
    });
  }, [announce]);

  const toggleToolbar = (): void => {
    if (toolbarOpen) {
      closeToolbar();
    } else {
      openToolbar();
    }
  };

  const stepChapter = useCallback(
    (delta: number): void => {
      if (bookBlockedReason(book, ageBand) !== null) {
        announce(`《${book.title}》${bookBlockedHint(book, ageBand)}`);
        return;
      }
      const next = idx + delta;
      if (next < 0) {
        announce('已是第一章。');
        return;
      }
      if (next >= book.chapters.length) {
        announce('已是最后一章。');
        return;
      }
      onChapterChange(next);
      announce(`已切到第 ${next + 1} 章 ${book.chapters[next].title}。`);
    },
    [ageBand, announce, book, idx, onChapterChange]
  );

  useEffect(() => {
    const handleKey = (event: KeyboardEvent): void => {
      const root = rootRef.current;

      if (event.key === 'Escape') {
        if (toolbarOpen) {
          event.preventDefault();
          closeToolbar();
          return;
        }
        setTipVisible(false);
        return;
      }

      if (event.key === 'Tab') {
        const items = collectFocusables(root);
        if (items.length === 0) {
          return;
        }
        const first = items[0];
        const last = items[items.length - 1];
        const active = document.activeElement;
        if (!root?.contains(active)) {
          event.preventDefault();
          first.focus();
        } else if (event.shiftKey && active === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && active === last) {
          event.preventDefault();
          first.focus();
        }
        return;
      }

      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') {
        return;
      }
      if (event.target instanceof HTMLElement && event.target.closest('input, textarea, select')) {
        return;
      }
      event.preventDefault();
      stepChapter(event.key === 'ArrowLeft' ? -1 : 1);
    };

    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [closeToolbar, stepChapter, toolbarOpen]);

  const toolbarHidden = !wide && !toolbarOpen;

  return (
    <div
      className="reader-overlay"
      role="dialog"
      aria-modal="true"
      aria-label={`阅读：${book.title}`}
      ref={rootRef}
    >
      <div className="reader-topbar">
        <button className="btn btn--ghost" type="button" onClick={onClose} aria-label="返回">
          <Icon name="chevron" size={20} style={{ transform: 'rotate(180deg)' }} />
          返回
        </button>
        <span className="reader-topbar__title">{book.title} · 第 {idx + 1} 章</span>
        <button
          className="btn btn--ghost"
          type="button"
          onClick={() => setShowNotes((value) => !value)}
          aria-pressed={showNotes}
          aria-label="切换边注"
        >
          <Icon name="note" size={20} />
        </button>
      </div>

      <div className="reader-shell" ref={shellRef} onScroll={handleReaderScroll}>
        <div className="reader-stage">
          <div
            className="reader-paper"
            data-read-theme={theme}
            style={{
              fontSize: `${fontSize}px`,
              lineHeight,
              paddingLeft: marginPx,
              paddingRight: marginPx,
            }}
          >
            <h2 className="reader-chapter-title">
              {isReal ? realTitle ?? `第 ${idx + 1} 章` : <>第 {idx + 1} 章 {chapter.title}</>}
            </h2>
            {isReal ? (
              realBody === null ? (
                <p className="muted-note">{realError ?? '正在获取正文'}</p>
              ) : null
            ) : (
              <p className="chapter-meta">
                本章字数：{chapter.words} 字 · 更新时间：{chapter.updatedAt}（示例）
              </p>
            )}
            {paragraphs.map((text, position) => (
              <p key={`${idx}-${position}`}>{text}</p>
            ))}
            {showNotes && !isReal && (
              <div className="inline-notes">
                <aside className="margin-note">
                  <span className="margin-note__tag">读者</span>
                  这段的市井气息很重，像是借旧物写人心。
                </aside>
                <aside className="margin-note">
                  <span className="margin-note__tag margin-note__tag--author">作者</span>
                  此处为后续伏笔，第三章回收。
                </aside>
              </div>
            )}

            <div className="reader-actions">
              <button className="btn btn--ghost" type="button" onClick={() => stepChapter(-1)}>上一章</button>
              <button className="btn btn--ghost" type="button" onClick={() => stepChapter(1)}>下一章</button>
            </div>
          </div>

          <button
            className="reader-tap"
            type="button"
            aria-label="点击唤出排版工具栏"
            ref={triggerRef}
            onClick={toggleToolbar}
          >
            Aa 排版
          </button>

          {tocOpen && (
            <div className="reader-toc" aria-label="章节目录">
              {book.chapters.map((item, position) => (
                <button
                  key={`${book.id}-toc-${position}`}
                  className={'toc-item' + (position === idx ? ' toc-here' : '')}
                  type="button"
                  onClick={() => {
                    onChapterChange(position);
                    setTocOpen(false);
                  }}
                >
                  第 {position + 1} 章 {item.title}
                </button>
              ))}
            </div>
          )}
        </div>

        <div
          className={'reader-toolbar' + (toolbarOpen ? ' is-open' : '')}
          role="group"
          aria-label="阅读排版设置（仅体验层）"
          aria-hidden={toolbarHidden || undefined}
          ref={toolbarRef}
        >
          <div className="reader-toolbar__row">
            <button className="btn btn--sm" type="button" onClick={() => setTocOpen((value) => !value)}>
              <Icon name="catalog" size={16} /> 目录
            </button>
            <button className="btn btn--sm" type="button" onClick={onToggleUiTheme} aria-pressed={uiTheme === 'night'}>
              <Icon name={uiTheme === 'night' ? 'moon' : 'sun'} size={16} /> 夜间
            </button>
            <button className="btn btn--sm toolbar-close-btn" type="button" onClick={closeToolbar}>
              收起排版
            </button>
          </div>

          <div className="reader-toolbar__row">
            <span className="reader-toolbar__label"><Icon name="text" size={16} /> 字号</span>
            <input
              type="range"
              min={14}
              max={22}
              step={1}
              value={fontSize}
              onChange={(event) => setFontSize(Number(event.target.value))}
              aria-label="字号"
            />
            <span className="reader-toolbar__val">{fontSize}px</span>
          </div>

          <div className="reader-toolbar__row">
            <span className="reader-toolbar__label"><Icon name="brightness" size={16} /> 行距</span>
            <div className="seg">
              {[1.6, 1.9, 2.2].map((value) => (
                <button
                  key={value}
                  type="button"
                  className={'seg__btn' + (lineHeight === value ? ' is-active' : '')}
                  aria-pressed={lineHeight === value}
                  onClick={() => setLineHeight(value)}
                >
                  {value === 1.6 ? '紧凑' : value === 1.9 ? '标准' : '宽松'}
                </button>
              ))}
            </div>
          </div>

          <div className="reader-toolbar__row">
            <span className="reader-toolbar__label"><Icon name="note" size={16} /> 页边距</span>
            <div className="seg">
              {[0, 1, 2].map((value) => (
                <button
                  key={value}
                  type="button"
                  className={'seg__btn' + (margin === value ? ' is-active' : '')}
                  aria-pressed={margin === value}
                  onClick={() => setMargin(value)}
                >
                  {value === 0 ? '窄' : value === 1 ? '标准' : '宽'}
                </button>
              ))}
            </div>
          </div>

          <div className="reader-toolbar__row">
            <span className="reader-toolbar__label">主题</span>
            <div className="seg">
              {READ_THEMES.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  className={'seg__btn' + (theme === item.id ? ' is-active' : '')}
                  aria-pressed={theme === item.id}
                  onClick={() => setTheme(item.id)}
                >
                  {item.label}
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>

      {tipVisible && (
        <div className="kb-tip" hidden={false}>试试使用键盘左右键切章吧！</div>
      )}
    </div>
  );
}
