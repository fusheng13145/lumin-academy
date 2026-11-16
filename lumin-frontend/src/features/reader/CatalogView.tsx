import { useEffect, useMemo, useState } from 'react';
import type { AgeBand, BookState, FilterState, SortKey, WordBucket } from '../../types';
import { BOOKS } from '../../mocks/mock';
import { BookCard } from './components/BookCard';
import {
  BAND_LABEL,
  POLICY_HINT,
  SORT_LABEL,
  STATE_LABEL,
  WORD_BUCKET_LABEL,
  bookBlockedHint,
  bookBlockedReason,
  filterBooks,
} from '../../lib/demoPolicy';

interface CatalogViewProps {
  ageBand: AgeBand;
  announce: (message: string) => void;
  onOpenDetail: (bookId: string) => void;
}

const AGE_OPTIONS: (AgeBand | 'all')[] = ['all', 'C0', 'C1', 'C2', 'C3', 'C4'];
const STATUS_OPTIONS: (BookState | 'all')[] = ['all', 'published', 'inreview', 'removed'];
const WORD_OPTIONS: WordBucket[] = ['all', 'lt10', 'w10_30', 'w30_60', 'gt60'];
const SORT_OPTIONS: SortKey[] = ['recent', 'title', 'words'];

const DEFAULT_FILTERS: FilterState = {
  age: 'all',
  status: 'all',
  words: 'all',
  sort: 'recent',
};

// 书城 R01：静态合规横幅 + 四维「与」组合筛选 + 单选排序 + 书卡网格
export function CatalogView({ ageBand, announce, onOpenDetail }: CatalogViewProps) {
  const [filters, setFilters] = useState<FilterState>(DEFAULT_FILTERS);
  const list = useMemo(() => filterBooks(BOOKS, filters), [filters]);

  useEffect(() => {
    announce(`筛选后共 ${list.length} 部作品。`);
  }, [list.length, announce]);

  const open = (bookId: string): void => {
    const book = BOOKS.find((item) => item.id === bookId);
    if (!book) {
      return;
    }
    if (bookBlockedReason(book, ageBand) !== null) {
      announce(`《${book.title}》${bookBlockedHint(book, ageBand)}`);
      return;
    }
    onOpenDetail(bookId);
  };

  return (
    <section aria-labelledby="catalog-h">
      <header className="page-head">
        <h1 id="catalog-h" tabIndex={-1}>书城</h1>
        <p className="page-head__sub">
          原创长篇阅读社区 · 按适龄、状态、字数与排序发现作品
        </p>
      </header>

      <div className="compliance-banner" role="note">
        适龄阅读 · 每一部作品都经过年龄分级审核后向适龄读者开放
      </div>

      <div className="filters">
        <div className="filter-row">
          <span className="filter-name">年龄带</span>
          <div className="filter-chips" role="group" aria-label="按年龄带筛选">
            {AGE_OPTIONS.map((value) => (
              <button
                key={value}
                type="button"
                className="chip"
                aria-pressed={filters.age === value}
                onClick={() => setFilters((prev) => ({ ...prev, age: value }))}
              >
                {value === 'all' ? '全部' : BAND_LABEL[value]}
              </button>
            ))}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-name">状态</span>
          <div className="filter-chips" role="group" aria-label="按作品状态筛选">
            {STATUS_OPTIONS.map((value) => (
              <button
                key={value}
                type="button"
                className="chip"
                aria-pressed={filters.status === value}
                onClick={() => setFilters((prev) => ({ ...prev, status: value }))}
              >
                {value === 'all' ? '全部' : STATE_LABEL[value]}
              </button>
            ))}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-name">字数</span>
          <div className="filter-chips" role="group" aria-label="按字数档筛选">
            {WORD_OPTIONS.map((value) => (
              <button
                key={value}
                type="button"
                className="chip"
                aria-pressed={filters.words === value}
                onClick={() => setFilters((prev) => ({ ...prev, words: value }))}
              >
                {WORD_BUCKET_LABEL[value]}
              </button>
            ))}
          </div>
        </div>

        <div className="filter-row">
          <span className="filter-name">排序</span>
          <div className="filter-chips" role="group" aria-label="选择排序方式">
            {SORT_OPTIONS.map((value) => (
              <button
                key={value}
                type="button"
                className="chip"
                aria-pressed={filters.sort === value}
                onClick={() => setFilters((prev) => ({ ...prev, sort: value }))}
              >
                {SORT_LABEL[value]}
              </button>
            ))}
          </div>
        </div>
      </div>

      <p className="results-count">共 {list.length} 部作品 · 排序：{SORT_LABEL[filters.sort]}</p>

      {list.length === 0 ? (
        <div className="empty">当前筛选条件下暂无作品，请调整筛选维度。</div>
      ) : (
        <div className="book-grid">
          {list.map((book) => (
            <BookCard key={book.id} book={book} ageBand={ageBand} onOpen={open} />
          ))}
        </div>
      )}

      <p className="data-note">
        示例数据（合成）· {POLICY_HINT} 书城不设搜索框与书架入口，不使用排行类机制、推荐流与轮播。
      </p>
    </section>
  );
}
