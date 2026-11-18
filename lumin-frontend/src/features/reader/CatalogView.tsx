import { useEffect, useMemo, useRef, useState } from 'react';
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

interface CatalogContentProps {
  ageBand: AgeBand;
  announce: (message: string) => void;
  onOpenDetail: (bookId: string) => void;
}

interface CatalogViewProps extends CatalogContentProps {
  source: 'live' | 'demo';
  onSourceChange: (source: 'live' | 'demo') => void;
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

type CatalogState =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'ready'; works: WorkListItemDto[] };

export function CatalogView({ source, onSourceChange, ...contentProps }: CatalogViewProps) {
  const [attempt, setAttempt] = useState(0);
  const headingRef = useRef<HTMLHeadingElement>(null);

  function retry() {
    headingRef.current?.focus();
    setAttempt((previous) => previous + 1);
  }

  return (
    <section className="catalog" aria-labelledby="catalog-h">
      <header className="page-head catalog-head">
        <h1 id="catalog-h" ref={headingRef} tabIndex={-1}>{source === 'demo' ? '示例书城' : '书城'}</h1>
        <button
          className="catalog-source"
          type="button"
          onClick={() => onSourceChange(source === 'demo' ? 'live' : 'demo')}
        >
          {source === 'demo' ? '返回书城' : '浏览示例'}
        </button>
      </header>
      {source === 'demo' ? (
        <DemoCatalog {...contentProps} />
      ) : (
        <LiveCatalog
          key={`${contentProps.ageBand}-${attempt}`}
          {...contentProps}
          onRetry={retry}
        />
      )}
    </section>
  );
}

function LiveCatalog({ ageBand, announce, onOpenDetail, onRetry }: CatalogContentProps & { onRetry: () => void }) {
  const [state, setState] = useState<CatalogState>({ status: 'loading' });

  useEffect(() => {
    let alive = true;
    announce('正在加载作品');
    void readerService.listWorks(ageBand).then(
      (works) => {
        if (alive) {
          setState({ status: 'ready', works });
          announce(works.length === 0 ? '暂无作品' : `共 ${works.length} 部作品`);
        }
      },
      () => {
        if (alive) {
          setState({ status: 'error' });
          announce('');
        }
      },
    );
    return () => { alive = false; };
  }, [ageBand, announce]);

  if (state.status === 'loading') {
    return <div className="catalog-state" aria-busy="true">正在加载作品</div>;
  }
  if (state.status === 'error') {
    return (
      <div className="catalog-state">
        <p role="alert">作品加载失败</p>
        <button className="btn" type="button" onClick={onRetry}>重试</button>
      </div>
    );
  }
  if (state.works.length === 0) {
    return <div className="catalog-state">暂无作品</div>;
  }

  return (
    <ul className="catalog-list" aria-label="作品">
      {state.works.map((work) => {
        const blocked = BAND_RANK[gradeBand(work.grade)] > BAND_RANK[ageBand];
        return (
          <li key={work.workId}>
            <button
              className="catalog-book"
              type="button"
              disabled={blocked}
              onClick={() => onOpenDetail(work.workId)}
            >
              <span className="catalog-book__main">
                <span className="catalog-book__title">{work.title}</span>
                <span className="catalog-book__author">{work.author}</span>
              </span>
              <span className="catalog-book__detail">
                {blocked ? '当前年龄带暂不可读' : gradeLabel(work.grade)}
              </span>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

function DemoCatalog({ ageBand, announce, onOpenDetail }: CatalogContentProps) {
  const [filters, setFilters] = useState<FilterState>(DEFAULT_FILTERS);
  const books = useMemo(() => filterBooks(BOOKS, filters), [filters]);

  useEffect(() => {
    announce(`示例书城，共 ${books.length} 部作品`);
  }, [books.length, announce]);

  return (
    <>
      <details className="catalog-filters">
        <summary>筛选</summary>
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
              <option key={value} value={value}>{SORT_LABEL[value]}</option>
            ))}
          </select>
          <button className="btn" type="button" onClick={() => setFilters(DEFAULT_FILTERS)}>清除筛选</button>
        </div>
      </details>
      {books.length === 0 ? (
        <div className="catalog-state">没有符合条件的作品</div>
      ) : (
        <ul className="catalog-list" aria-label="示例作品">
          {books.map((book) => (
            <li key={book.id}><BookCard book={book} ageBand={ageBand} onOpen={onOpenDetail} /></li>
          ))}
        </ul>
      )}
    </>
  );
}
