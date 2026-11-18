import type { ReadingProgress } from '../types';
import { FEATURED_BOOK, MOBILE_BOOKS, findBook } from '../data/library';
import { BookCover, Cover, SectionHead } from '../components/ui';
import {
  IconArrowUpRight,
  IconChevronRight,
  IconPen,
  IconPlay,
  IconSearch,
} from '../components/Icons';

interface HomeScreenProps {
  progress: ReadingProgress | null;
  onOpenBook: (bookId: string) => void;
  onOpenReader: (bookId: string, chapterNo: number) => void;
  onSearch: () => void;
  onStudio: () => void;
  onToast: (message: string) => void;
}

export function HomeScreen({
  progress,
  onOpenBook,
  onOpenReader,
  onSearch,
  onStudio,
  onToast,
}: HomeScreenProps) {
  const continueBook = progress ? findBook(progress.bookId) : undefined;

  return (
    <div className="m-page m-enter">
      <header className="m-topbar" style={{ margin: '0 -20px 8px' }}>
        <div className="m-topbar__inner">
          <div className="m-brand">
            <span className="m-brand__mark" aria-hidden="true">
              启
            </span>
            <span className="m-brand__name">启明书院</span>
            <span className="m-brand__edition">LUMIN</span>
          </div>
          <button className="m-icon-btn" type="button" onClick={onSearch} aria-label="搜索作品">
            <IconSearch />
          </button>
        </div>
      </header>

      <section className="m-hero" aria-label="主编推荐">
        <div className="m-hero__backdrop" aria-hidden="true" />
        <div className="m-hero__body">
          <div className="m-hero__top">
            <div>
              <p className="m-hero__label">本周主编推荐 · 01</p>
              <p className="m-caption" style={{ color: 'rgba(247,242,232,.56)', marginTop: 6 }}>
                {FEATURED_BOOK.category} · 连载中
              </p>
            </div>
            <span className="m-badge" style={{ background: 'rgba(247,242,232,.12)', color: '#f7f2e8' }}>
              {FEATURED_BOOK.stats.recommend}% 推荐
            </span>
          </div>

          <BookCover book={FEATURED_BOOK} className="m-hero__cover" />

          <div className="m-hero__text">
            <h1 className="m-hero__title">{FEATURED_BOOK.title}</h1>
            <p className="m-hero__sub">{FEATURED_BOOK.summary}</p>
            <div className="m-hero__cta">
              <button
                className="m-btn m-btn--primary"
                type="button"
                onClick={() => onOpenReader(FEATURED_BOOK.id, 1)}
              >
                <IconPlay size={16} />
                开始阅读
              </button>
              <button
                className="m-btn m-hero__ghost"
                type="button"
                onClick={() => onToast('已加入书架，可在「我的」中查看')}
              >
                加入书架
              </button>
            </div>
          </div>
        </div>
      </section>

      {continueBook && progress ? (
        <button
          className="m-continue"
          type="button"
          onClick={() => onOpenReader(continueBook.id, progress.chapterNo)}
        >
          <div
            className="m-continue__thumb"
            style={{ background: continueBook.cover[0], color: continueBook.cover[1] }}
            aria-hidden="true"
          >
            {continueBook.title.slice(0, 1)}
          </div>
          <div className="m-continue__main">
            <p className="m-continue__title">继续阅读 · {continueBook.title}</p>
            <p className="m-continue__meta">
              第 {progress.chapterNo} 章 · 上次读到这里
            </p>
            <div className="m-progress" aria-hidden="true">
              <div className="m-progress__bar" style={{ width: `${Math.max(8, progress.percent)}%` }} />
            </div>
          </div>
          <IconChevronRight size={18} />
        </button>
      ) : (
        <div className="m-continue" style={{ display: 'block' }}>
          <p className="m-eyebrow">今日开始</p>
          <p className="m-serif" style={{ margin: '6px 0 0', fontSize: 18, fontWeight: 700 }}>
            从《{FEATURED_BOOK.title}》第一章读起
          </p>
          <p className="m-caption" style={{ marginTop: 5 }}>
            共 {FEATURED_BOOK.chapters.length} 章 · 约 {FEATURED_BOOK.wt} 字
          </p>
        </div>
      )}

      <section className="m-section">
        <SectionHead title="正在发生的故事" eyebrow="NEW & NOTEWORTHY" action="全部" onAction={onSearch} />
        <div className="m-shelf">
          {MOBILE_BOOKS.map((book) => (
            <button
              key={book.id}
              className="m-shelf__item"
              type="button"
              onClick={() => onOpenBook(book.id)}
            >
              <BookCover book={book} />
              <p className="m-shelf__title">{book.title}</p>
              <p className="m-shelf__meta">
                {book.author} · {book.category}
              </p>
            </button>
          ))}
        </div>
      </section>

      <section className="m-section">
        <SectionHead title="编辑精选" eyebrow="EDITOR'S PICK" />
        <div className="m-editorial">
          {MOBILE_BOOKS.slice(1, 4).map((book) => (
            <button
              key={book.id}
              className="m-editorial__item"
              type="button"
              onClick={() => onOpenBook(book.id)}
              style={{ border: 0, background: 'transparent', textAlign: 'left', color: 'inherit' }}
            >
              <div style={{ width: 92 }}>
                <Cover
                  title={book.title}
                  author={book.author}
                  cover={book.cover}
                  className="m-cover--sm"
                />
              </div>
              <div>
                <h3 className="m-editorial__title">{book.title}</h3>
                <p className="m-editorial__desc">{book.editorNote}</p>
                <div className="m-editorial__meta">
                  <span className="m-badge">{book.category}</span>
                  <span className="m-badge">{book.stats.reads} 阅读</span>
                </div>
              </div>
            </button>
          ))}
        </div>
      </section>

      <section className="m-section">
        <div className="m-card m-card--pad" style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
          <div
            style={{
              width: 44,
              height: 44,
              borderRadius: 12,
              background: 'var(--m-accent-soft)',
              color: 'var(--m-accent-deep)',
              display: 'grid',
              placeItems: 'center',
              flex: 'none',
            }}
          >
            <IconPen size={20} />
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <p className="m-eyebrow">作者专区</p>
            <p className="m-h3" style={{ marginTop: 3 }}>
              把故事写下去
            </p>
            <p className="m-caption" style={{ marginTop: 3 }}>
              数据、写作、AI 创作台，都在一个工作台里。
            </p>
          </div>
          <button className="m-btn m-btn--soft m-btn--sm" type="button" onClick={onStudio}>
            进入
            <IconArrowUpRight size={15} />
          </button>
        </div>
      </section>

      <footer style={{ marginTop: 34, paddingBottom: 8 }}>
        <hr className="m-rule" />
        <p className="m-caption" style={{ textAlign: 'center' }}>
          启明书院 · 原创示例内容
          <br />
          LUMIN ACADEMY / MOBILE EDITION
        </p>
      </footer>
    </div>
  );
}
