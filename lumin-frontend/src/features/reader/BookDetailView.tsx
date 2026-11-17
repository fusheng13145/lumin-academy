import type { CSSProperties } from 'react';
import type { AgeBand, Book } from '../../types';
import {
  BAND_LABEL,
  STATE_LABEL,
  bookBlockedHint,
  bookBlockedReason,
} from '../../lib/demoPolicy';

interface BookDetailViewProps {
  book: Book;
  ageBand: AgeBand;
  onBack: () => void;
  onRead: (bookId: string, chapterIdx: number) => void;
}

// 书详情：封面 / 元信息 / 简介 / 章节目录
export function BookDetailView({ book, ageBand, onBack, onRead }: BookDetailViewProps) {
  const reason = bookBlockedReason(book, ageBand);
  const blocked = reason !== null;
  const coverStyle = {
    '--cover-a': book.cover[0],
    '--cover-b': book.cover[1],
  } as CSSProperties;
  const ctaLabel = blocked ? '暂不可读' : '开始阅读';

  return (
    <section aria-labelledby="detail-h">
      <nav className="breadcrumb" aria-label="面包屑">
        <button className="crumb" type="button" onClick={onBack}>书城</button>
        <span className="crumb-sep" aria-hidden="true">/</span>
        <span className="crumb-current" aria-current="page">{book.title}</span>
      </nav>

      <div className="book-head" style={coverStyle}>
        <div className="book-cover book-cover--lg" aria-hidden="true">
          <span className="cover-title">{book.title}</span>
          <span className="cover-author">{book.author}</span>
        </div>
        <div className="book-head-info">
          <h1 id="detail-h" tabIndex={-1}>{book.title}</h1>
          <div className="book-badges">
            <span className="badge badge--age">{BAND_LABEL[book.band]}</span>
            <span className={'badge ' + (book.state === 'removed' ? 'badge--removed' : book.state === 'inreview' ? 'badge--review' : 'badge--published')}>
              {STATE_LABEL[book.state]}
            </span>
          </div>
          <p className="book-meta">{[book.author, book.wt, book.category].filter(Boolean).join(' · ')}</p>
          {blocked && <p className="muted-note">{bookBlockedHint(book, ageBand)}</p>}

          <div className="btn-row">
            <button
              type="button"
              className="btn btn--primary"
              disabled={blocked}
              onClick={() => onRead(book.id, 0)}
            >
              {ctaLabel}
            </button>
          </div>
        </div>
      </div>

      <h2 className="section-title">简介</h2>
      <p className="detail-intro">{book.intro}</p>

      <h2 className="section-title">目录</h2>
      <div className="chapter-list">
        {book.chapters.map((chapter, index) => (
          <button
            key={`${book.id}-${index}`}
            type="button"
            className="chapter-item"
            disabled={blocked}
            onClick={() => onRead(book.id, index)}
          >
            <span>第 {index + 1} 章 {chapter.title}</span>
            <span className="ch-date">{chapter.updatedAt}</span>
          </button>
        ))}
      </div>
    </section>
  );
}
