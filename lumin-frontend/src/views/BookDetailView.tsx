import type { CSSProperties } from 'react';
import type { AgeBand, Book } from '../types';
import {
  BAND_LABEL,
  STATE_LABEL,
  bookBlockedHint,
  bookBlockedReason,
} from '../lib/demoPolicy';

interface BookDetailViewProps {
  book: Book;
  ageBand: AgeBand;
  onBack: () => void;
  onRead: (bookId: string, chapterIdx: number) => void;
}

interface Volume {
  label: string;
  from: number;
  to: number;
}

function splitVolumes(total: number): Volume[] {
  const count = total > 8 ? 2 : 1;
  const per = Math.ceil(total / count);
  const volumes: Volume[] = [];
  for (let i = 0; i < count; i += 1) {
    const from = i * per;
    volumes.push({
      label: i === 0 ? '第一卷' : '第二卷',
      from,
      to: Math.min(total, from + per),
    });
  }
  return volumes;
}

// R02 书详情：面包屑 / 书头 / 作者卡 / 简介 / 卷分组章节目录 / CTA
export function BookDetailView({ book, ageBand, onBack, onRead }: BookDetailViewProps) {
  const reason = bookBlockedReason(book, ageBand);
  const blocked = reason !== null;
  const coverStyle = {
    '--cover-a': book.cover[0],
    '--cover-b': book.cover[1],
  } as CSSProperties;
  const ctaLabel = reason === 'removed'
    ? '开始阅读（当前不可访问）'
    : reason === 'muted'
      ? '开始阅读（适龄超出演示年龄带）'
      : '开始阅读';

  return (
    <section aria-labelledby="detail-h">
      <nav className="breadcrumb" aria-label="面包屑">
        <button className="crumb" type="button" onClick={onBack}>首页</button>
        <span className="crumb-sep" aria-hidden="true">/</span>
        <span className="crumb-current" aria-current="page">《{book.title}》</span>
      </nav>

      <div className="book-head" style={coverStyle}>
        <div className="book-cover book-cover--lg" aria-hidden="true">
          <span className="cover-title">{book.title}</span>
          <span className="cover-author">{book.author}</span>
        </div>
        <div className="book-head-info">
          <h1 id="detail-h" tabIndex={-1}>《{book.title}》</h1>
          <div className="book-badges">
            <span className="badge badge--age">{BAND_LABEL[book.band]}</span>
            <span className={'badge ' + (book.state === 'removed' ? 'badge--removed' : book.state === 'inreview' ? 'badge--review' : 'badge--published')}>
              {STATE_LABEL[book.state]}
            </span>
            {book.tags.map((tag) => (
              <span className="badge badge--neutral" key={tag}>{tag}</span>
            ))}
          </div>
          <p className="book-meta">字数：{book.wt} · 分类：{book.category}</p>
          {blocked && <p className="muted-note">{bookBlockedHint(book, ageBand)}</p>}

          <p className="updated-line">
            最近更新：第 {book.chapters.length} 章 {book.chapters[book.chapters.length - 1].title} · {book.relativeTime}
          </p>

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

          <div className="author-card">
            <div className="avatar-circle" aria-hidden="true">{book.author.charAt(0)}</div>
            <div>
              <p className="author-name">{book.author}（示例）</p>
              <p className="author-sig">签名：{book.sig}</p>
            </div>
          </div>
        </div>
      </div>

      <h2 className="section-title">作品简介</h2>
      <p className="detail-intro">{book.intro}</p>

      <h2 className="section-title">章节目录</h2>
      {splitVolumes(book.chapters.length).map((volume) => (
        <div key={volume.label}>
          <p className="vol-title">
            {volume.label} · 共 {volume.to - volume.from} 章
          </p>
          <div className="chapter-list">
            {book.chapters.slice(volume.from, volume.to).map((chapter, offset) => {
              const index = volume.from + offset;
              return (
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
              );
            })}
          </div>
        </div>
      ))}

      <p className="data-note">
        示例数据（合成）· 目录章节可直接定位阅读。年龄带判定仅演示，不替代服务端 AgePolicy / VisibilityPolicy 裁决。
      </p>
    </section>
  );
}
