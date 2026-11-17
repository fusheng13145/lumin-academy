import { useEffect, useMemo, useState } from 'react';
import type { AgeBand, BookState, FilterState, SortKey, WordBucket } from '../../types';
import { BOOKS } from '../../mocks/mock';
import { readerService } from '../../services/readerService';
import type { WorkListItemDto } from '../../services/readerService';
import { gradeBand, gradeLabel } from '../../lib/realBook';
import { BookCard } from './components/BookCard';
import {
  BAND_LABEL,
  BAND_RANK,
  SORT_LABEL,
  STATE_LABEL,
  WORD_BUCKET_LABEL,
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

// 书城：真实读者链为主数据源；API 不可用时回退演示夹具（保留筛选体验）。
export function CatalogView({ ageBand, announce, onOpenDetail }: CatalogViewProps) {
  const [filters, setFilters] = useState<FilterState>(DEFAULT_FILTERS);
  const [works, setWorks] = useState<WorkListItemDto[] | null>(null);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const list = await readerService.listWorks();
        if (alive) {
          setWorks(list);
        }
      } catch {
        if (alive) {
          setWorks([]); // 空数组视为不可用 → 回退演示夹具
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const demoMode = works === null || works.length === 0;
  const demoList = useMemo(() => filterBooks(BOOKS, filters), [filters]);

  useEffect(() => {
    if (demoMode) {
      announce(`共 ${demoList.length} 部作品`);
    } else if (works) {
      announce(`共 ${works.length} 部作品`);
    }
  }, [demoMode, demoList.length, works, announce]);

  // 真实作品：展示层灰显用 grade→band 派生口径，真实门禁在后端（未授权 404）。
  const openWork = (work: WorkListItemDto): void => {
    const band = gradeBand(work.grade);
    if (BAND_RANK[band] > BAND_RANK[ageBand]) {
      announce(`《${work.title}》当前年龄带暂不可读`);
      return;
    }
    onOpenDetail(work.workId);
  };

  if (!demoMode) {
    return (
      <section aria-labelledby="catalog-h">
        <header className="page-head">
          <h1 id="catalog-h" tabIndex={-1}>书城</h1>
        </header>
        {works!.map((work) => (
          <button
            key={work.workId}
            type="button"
            className="chapter-item"
            onClick={() => openWork(work)}
          >
            <span>《{work.title}》 {work.author}</span>
            <span className="ch-date">{gradeLabel(work.grade)}</span>
          </button>
        ))}
      </section>
    );
  }

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

      {demoList.length === 0 ? (
        <div className="empty">暂无作品</div>
      ) : (
        <div className="book-grid">
          {demoList.map((book) => (
            <BookCard key={book.id} book={book} ageBand={ageBand} onOpen={onOpenDetail} />
          ))}
        </div>
      )}
    </section>
  );
}
