import type { CSSProperties, KeyboardEvent } from 'react';
import type { AgeBand, Book, BookState } from '../types';
import {
  BAND_LABEL,
  STATE_LABEL,
  bookBlockedHint,
  bookBlockedReason,
} from '../lib/demoPolicy';

interface BookCardProps {
  book: Book;
  ageBand: AgeBand;
  onOpen: (bookId: string) => void;
}

const STATE_CLS: Record<BookState, string> = {
  published: 'badge--published',
  inreview: 'badge--review',
  removed: 'badge--removed',
};

// 圆角书封卡片：CSS 排版（书脊 / 竖排书名 / 署名），无外部图片。
// 灰显书不挂 onClick、不进 tab 序（QA 第 2 轮 E-FAIL 口径）。
export function BookCard({ book, ageBand, onOpen }: BookCardProps) {
  const reason = bookBlockedReason(book, ageBand);
  const blocked = reason !== null;
  const coverStyle = {
    '--cover-a': book.cover[0],
    '--cover-b': book.cover[1],
  } as CSSProperties;

  const label = `《${book.title}》，作者 ${book.author}（示例），${BAND_LABEL[book.band]}，${STATE_LABEL[book.state]}，${book.wt}，更新于 ${book.relativeTime}。${blocked ? bookBlockedHint(book, ageBand) : '点击查看书籍详情。'}`;

  const handleKeyDown = (event: KeyboardEvent<HTMLElement>): void => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onOpen(book.id);
    }
  };

  return (
    <article
      className={'book-card' + (blocked ? ' is-muted' : '')}
      role={blocked ? undefined : 'button'}
      tabIndex={blocked ? undefined : 0}
      aria-disabled={blocked || undefined}
      aria-label={label}
      style={coverStyle}
      onClick={blocked ? undefined : () => onOpen(book.id)}
      onKeyDown={blocked ? undefined : handleKeyDown}
    >
      <div className="book-cover" aria-hidden="true">
        <span className="cover-title">{book.title}</span>
        <span className="cover-author">{book.author}</span>
      </div>
      <div className="book-card__body">
        <h3 className="book-title">《{book.title}》</h3>
        <p className="book-meta">
          <span>{book.author}（示例） · {book.category}</span>
          <span>{book.wt}</span>
          <span>更新于 {book.relativeTime}</span>
        </p>
        <div className="book-card__meta">
          <span className="badge badge--age">{BAND_LABEL[book.band]}</span>
          <span className={'badge ' + STATE_CLS[book.state]}>
            {STATE_LABEL[book.state]}
          </span>
        </div>
        {blocked && <p className="muted-note">{bookBlockedHint(book, ageBand)}</p>}
      </div>
    </article>
  );
}
