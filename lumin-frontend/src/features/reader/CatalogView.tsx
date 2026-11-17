import { useEffect, useMemo, useState } from 'react';
import type { AgeBand, BookState, FilterState, SortKey, WordBucket } from '../../types';
import { BOOKS } from '../../mocks/mock';
import { BookCard } from './components/BookCard';
import {
  BAND_LABEL,
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

// 书城：筛选 + 书卡网格
export function CatalogView({ ageBand, announce, onOpenDetail }: CatalogViewProps) {
  const [filters, setFilters] = useState<FilterState>(DEFAULT_FILTERS);
  const list = useMemo(() => filterBooks(BOOKS, filters), [filters]);

  useEffect(() => {
    announce(`共 ${list.length} 部作品`);
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
      </header>

      <div className="toolbar">
        <select
          className="select"
          aria-label="按年龄带筛选"
          value={filters.age}
          onChange={(event) => setFilters((prev) => ({ ...prev, age: event.target.value as AgeBand | 'all' }))}
        >
          {AGE_OPTIONS.map((value) => (
            <option key={value} value={value}>{value === 'all' ? '全部年龄' : BAND_LABEL[value]}</option>
          ))}
        </select>

        <select
          className="select"
          aria-label="按状态筛选"
          value={filters.status}
          onChange={(event) => setFilters((prev) => ({ ...prev, status: event.target.value as BookState | 'all' }))}
        >
          {STATUS_OPTIONS.map((value) => (
            <option key={value} value={value}>{value === 'all' ? '全部状态' : STATE_LABEL[value]}</option>
          ))}
        </select>

        <select
          className="select"
          aria-label="按字数筛选"
          value={filters.words}
          onChange={(event) => setFilters((prev) => ({ ...prev, words: event.target.value as WordBucket }))}
        >
          {WORD_OPTIONS.map((value) => (
            <option key={value} value={value}>{WORD_BUCKET_LABEL[value]}</option>
          ))}
        </select>

        <select
          className="select"
          aria-label="排序方式"
          value={filters.sort}
          onChange={(event) => setFilters((prev) => ({ ...prev, sort: event.target.value as SortKey }))}
        >
          {SORT_OPTIONS.map((value) => (
            <option key={value} value={value}>排序：{SORT_LABEL[value]}</option>
          ))}
        </select>
      </div>

      {list.length === 0 ? (
        <div className="empty">暂无作品</div>
      ) : (
        <div className="book-grid">
          {list.map((book) => (
            <BookCard key={book.id} book={book} ageBand={ageBand} onOpen={open} />
          ))}
        </div>
      )}
    </section>
  );
}
