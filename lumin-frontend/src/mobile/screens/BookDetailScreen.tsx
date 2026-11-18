import { useState } from 'react';
import type { MobileBook } from '../types';
import { MOBILE_BOOKS } from '../data/library';
import { BookCover, Cover, SectionHead, TopBar } from '../components/ui';
import {
  IconChevronRight,
  IconDownload,
  IconHeart,
  IconPlay,
  IconShare,
} from '../components/Icons';

interface BookDetailScreenProps {
  book: MobileBook;
  onBack: () => void;
  onRead: (chapterNo: number) => void;
  onOpenBook: (bookId: string) => void;
  onToast: (message: string) => void;
}

export function BookDetailScreen({ book, onBack, onRead, onOpenBook, onToast }: BookDetailScreenProps) {
  const [saved, setSaved] = useState(false);
  const [showAll, setShowAll] = useState(false);
  const chapters = showAll ? book.chapters : book.chapters.slice(0, 6);
  const related = MOBILE_BOOKS.filter((item) => item.id !== book.id).slice(0, 3);

  return (
    <div className="m-page m-page--flush m-enter">
      <div style={{ padding: '0 20px' }}>
        <TopBar title="作品详情" subtitle="WORK" onBack={onBack} />
      </div>

      <section className="m-detail-hero" style={{ paddingInline: 20 }}>
        <div className="m-detail-hero__grid">
          <BookCover book={book} className="m-detail-hero__cover" />
          <div className="m-detail-hero__main">
            <p className="m-eyebrow">{book.category} · {book.state === 'published' ? '连载中' : '筹备中'}</p>
            <h1 className="m-detail-hero__title">{book.title}</h1>
            <p className="m-detail-hero__author">著 · {book.author}</p>
            <div className="m-editorial__meta" style={{ marginTop: 10 }}>
              {book.tags.slice(0, 3).map((tag) => (
                <span key={tag} className="m-badge">
                  {tag}
                </span>
              ))}
            </div>
          </div>
        </div>

        <div className="m-detail-stats">
          <div className="m-detail-stat">
            <span className="m-detail-stat__value">{book.stats.reads}</span>
            <span className="m-detail-stat__label">阅读</span>
          </div>
          <div className="m-detail-stat">
            <span className="m-detail-stat__value">{book.stats.favorites}</span>
            <span className="m-detail-stat__label">收藏</span>
          </div>
          <div className="m-detail-stat">
            <span className="m-detail-stat__value">{book.stats.recommend}%</span>
            <span className="m-detail-stat__label">推荐</span>
          </div>
        </div>

        <div className="m-detail-actions">
          <button className="m-btn m-btn--primary" type="button" onClick={() => onRead(1)}>
            <IconPlay size={16} />
            开始阅读
          </button>
          <button
            className={`m-btn ${saved ? 'm-btn--soft' : ''}`}
            type="button"
            onClick={() => {
              setSaved((value) => !value);
              onToast(saved ? '已从书架移除' : '已加入书架');
            }}
            aria-pressed={saved}
          >
            <IconHeart size={16} />
            {saved ? '已收藏' : '收藏'}
          </button>
          <button className="m-icon-btn" type="button" onClick={() => onToast('分享链接已复制到剪贴板')} aria-label="分享">
            <IconShare size={19} />
          </button>
        </div>
      </section>

      <div style={{ padding: '0 20px' }}>
        <section className="m-section" style={{ marginTop: 18 }}>
          <SectionHead title="作品简介" eyebrow="ABOUT THIS STORY" />
          <p className="m-body">{book.intro}</p>
          <p className="m-caption" style={{ marginTop: 10 }}>
            {book.wt} 字 · {book.chapters.length} 章 · 最近更新 {book.relativeTime}
          </p>
        </section>

        <section className="m-section">
          <div className="m-card m-card--pad" style={{ borderLeft: '3px solid var(--m-accent)' }}>
            <p className="m-eyebrow">编辑手记</p>
            <p className="m-body" style={{ marginTop: 7, color: 'var(--m-ink)' }}>
              {book.editorNote}
            </p>
          </div>
        </section>

        <section className="m-section">
          <SectionHead
            title="章节目录"
            eyebrow={`${book.chapters.length} CHAPTERS`}
            action={showAll ? '收起' : '全部'}
            onAction={() => setShowAll((value) => !value)}
          />
          <div className="m-chapter-list">
            {chapters.map((chapter) => (
              <button
                key={chapter.id}
                className="m-chapter-row"
                type="button"
                onClick={() => onRead(chapter.no)}
              >
                <span className="m-chapter-row__no">{String(chapter.no).padStart(2, '0')}</span>
                <span className="m-chapter-row__title">{chapter.title}</span>
                <span className="m-chapter-row__meta">{chapter.words} 字</span>
                <IconChevronRight size={15} />
              </button>
            ))}
          </div>
          {!showAll ? (
            <button className="m-btn m-btn--block m-btn--sm" type="button" onClick={() => setShowAll(true)} style={{ marginTop: 14 }}>
              查看全部 {book.chapters.length} 章
            </button>
          ) : null}
        </section>

        <section className="m-section">
          <SectionHead title="读者也在读" eyebrow="MORE LIKE THIS" />
          <div className="m-shelf" style={{ marginInline: -20, paddingInline: 20 }}>
            {related.map((item) => (
              <button key={item.id} className="m-shelf__item" type="button" onClick={() => onOpenBook(item.id)}>
                <Cover title={item.title} author={item.author} cover={item.cover} />
                <p className="m-shelf__title">{item.title}</p>
                <p className="m-shelf__meta">{item.stats.reads} 阅读</p>
              </button>
            ))}
          </div>
        </section>

        <button className="m-btn m-btn--block" type="button" onClick={() => onToast('下载功能将在 P1 离线阅读中开放')} style={{ marginTop: 4 }}>
          <IconDownload size={17} />
          离线下载（P1）
        </button>
      </div>
    </div>
  );
}
