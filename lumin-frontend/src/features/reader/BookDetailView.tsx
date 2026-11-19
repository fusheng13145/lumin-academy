import { useRef } from 'react';
import type { AgeBand, Book } from '../../types';
import { bookBlockedHint, bookBlockedReason } from '../../lib/demoPolicy';

export type BookDetailState =
  | { status: 'loading' }
  | { status: 'error'; onRetry: () => void }
  | { status: 'ready'; book: Book };

/** 追更订阅槽位（UGC-036 收口：详情页订阅入口；真实路径传入）。 */
export interface DetailSubscription {
  subscribed: boolean;
  busy: boolean;
  error: string | null;
  onToggle: () => void;
}

interface BookDetailViewProps {
  state: BookDetailState;
  ageBand: AgeBand;
  onBack: () => void;
  onRead: (bookId: string, chapterIdx: number) => void;
  subscription?: DetailSubscription | null;
}

export function BookDetailView({ state, ageBand, onBack, onRead, subscription = null }:
  BookDetailViewProps) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const book = state.status === 'ready' ? state.book : null;
  const blocked = book !== null && bookBlockedReason(book, ageBand) !== null;

  return (
    <section className="book-detail" aria-labelledby="detail-h" aria-busy={state.status === 'loading'}>
      <nav className="detail-back" aria-label="返回">
        <button className="btn btn--ghost" type="button" onClick={onBack}>书城</button>
      </nav>
      <header className="detail-head">
        <div className="detail-heading">
          <h1 id="detail-h" ref={headingRef} tabIndex={-1}>{book?.title ?? '作品详情'}</h1>
          {book && <p className="detail-author">{book.author}</p>}
        </div>
        {book && (
          <div className="btn-row">
            <button type="button" className="btn btn--primary"
              disabled={blocked || book.chapters.length === 0}
              onClick={() => onRead(book.id, 0)}>
              {blocked ? '暂不可读' : '开始阅读'}
            </button>
            {subscription !== null && !blocked && (
              <button type="button" className="btn btn--soft"
                disabled={subscription.busy}
                aria-pressed={subscription.subscribed}
                onClick={subscription.onToggle}>
                {subscription.subscribed ? '已追更 · 点击退订' : '追更更新'}
              </button>
            )}
          </div>
        )}
      </header>

      {state.status === 'loading' && <p className="detail-state">正在加载作品</p>}
      {state.status === 'error' && (
        <div className="detail-state">
          <p role="alert">作品加载失败</p>
          <button className="btn btn--soft" type="button" onClick={() => {
            headingRef.current?.focus();
            state.onRetry();
          }}>重试</button>
        </div>
      )}
      {book && (
        <>
          {blocked && <p className="detail-restriction">{bookBlockedHint(book, ageBand)}</p>}
          {subscription?.error !== null && subscription?.error !== undefined
            && subscription.error !== '' && (
            <p className="detail-restriction" role="alert">{subscription.error}</p>
          )}
          {book.intro.trim() && <p className="detail-intro">{book.intro}</p>}
          <h2 className="section-title">目录</h2>
          {book.chapters.length === 0 ? <p className="detail-state">暂无章节</p> : (
            <ul className="chapter-list" aria-label="章节目录">
              {book.chapters.map((chapter, index) => (
                <li key={`${book.id}-${index}`}>
                  <button type="button" className="chapter-item" disabled={blocked}
                    onClick={() => onRead(book.id, index)}>
                    <span>第 {index + 1} 章 {chapter.title}</span>
                    {chapter.updatedAt && <span className="ch-date">{chapter.updatedAt}</span>}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </section>
  );
}
