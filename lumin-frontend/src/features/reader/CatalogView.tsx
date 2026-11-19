import { useEffect, useMemo, useRef, useState } from 'react';
import type { AgeBand, BookState, FilterState, SortKey, WordBucket } from '../../types';
import { BOOKS } from '../../mocks/mock';
import { readerService } from '../../services/readerService';
import type { FeaturedItemDto, WorkListItemDto } from '../../services/readerService';
import { EXPOSURE_LABEL } from '../../services/curationService';
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
  | { status: 'ready'; works: WorkListItemDto[]; nextCursor: string | null };

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
  const [searchInput, setSearchInput] = useState('');
  const [submittedQuery, setSubmittedQuery] = useState<string | null>(null);

  return (
    <div>
      <form
        className="catalog-search"
        role="search"
        onSubmit={(event) => {
          event.preventDefault();
          const trimmed = searchInput.trim();
          setSubmittedQuery(trimmed === '' ? null : trimmed);
        }}
      >
        <input
          className="input"
          type="search"
          aria-label="搜索作品"
          placeholder="搜索标题或作者"
          value={searchInput}
          maxLength={100}
          onChange={(event) => setSearchInput(event.target.value)}
        />
        <button className="btn" type="submit">搜索</button>
        {submittedQuery !== null ? (
          <button
            className="btn btn--ghost"
            type="button"
            onClick={() => { setSearchInput(''); setSubmittedQuery(null); }}
          >
            清除搜索
          </button>
        ) : null}
      </form>
      {/* key 重挂载：查询/账号变化即回到 loading（重试经父级 attempt 整体重挂载），
          避免 effect 内同步 setState */}
      <LiveCatalogContent
        key={submittedQuery ?? 'browse'}
        submittedQuery={submittedQuery}
        ageBand={ageBand}
        announce={announce}
        onOpenDetail={onOpenDetail}
        onRetry={onRetry}
      />
    </div>
  );
}

function LiveCatalogContent({ submittedQuery, ageBand, announce, onOpenDetail, onRetry }: CatalogContentProps & { submittedQuery: string | null; onRetry: () => void }) {
  const [state, setState] = useState<CatalogState>({ status: 'loading' });
  const [loadingMore, setLoadingMore] = useState(false);
  const [featured, setFeatured] = useState<FeaturedItemDto[]>([]);


  // 浏览（无关键词）或搜索（UGC-038）：空结果与不可用分开呈现——
  // 搜索无命中是「没有找到相关作品」，请求失败是「搜索加载失败」。
  useEffect(() => {
    let alive = true;
    if (submittedQuery === null) {
      announce('正在加载作品');
      void readerService.listWorks(ageBand).then(
        (works) => {
          if (alive) {
            setState({ status: 'ready', works, nextCursor: null });
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
    }
    announce('正在搜索');
    void readerService.searchWorks(submittedQuery, ageBand).then(
      (result) => {
        if (alive) {
          setState({ status: 'ready', works: result.items, nextCursor: result.nextCursor });
          announce(result.items.length === 0 ? '没有找到相关作品' : `找到 ${result.items.length} 部作品`);
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
  }, [ageBand, submittedQuery, announce]);

  // 运营精选位（UGC-039）：可见性由服务端同源裁决；每项干预标识显式。
  useEffect(() => {
    let alive = true;
    void readerService.listFeatured(ageBand).then(
      (items) => {
        if (alive) {
          setFeatured(items);
        }
      },
      () => {
        if (alive) {
          setFeatured([]);
        }
      },
    );
    return () => { alive = false; };
  }, [ageBand]);

  const loadMore = () => {
    if (submittedQuery === null || state.status !== 'ready'
        || state.nextCursor === null || loadingMore) {
      return;
    }
    const cursor = state.nextCursor;
    setLoadingMore(true);
    void readerService.searchWorks(submittedQuery, ageBand, cursor).then(
      (result) => {
        setLoadingMore(false);
        setState((previous) => previous.status === 'ready'
          ? { status: 'ready', works: dedupeWorks(previous.works, result.items),
              nextCursor: result.nextCursor }
          : previous);
      },
      () => {
        setLoadingMore(false);
        announce('加载更多失败，可稍后重试');
      },
    );
  };

  if (state.status === 'loading') {
    return <div className="catalog-state" aria-busy="true">
      {submittedQuery === null ? '正在加载作品' : '正在搜索'}
    </div>;
  }
  if (state.status === 'error') {
    return (
      <div className="catalog-state">
        <p role="alert">{submittedQuery === null ? '作品加载失败' : '搜索加载失败'}</p>
        <button className="btn" type="button" onClick={onRetry}>重试</button>
      </div>
    );
  }

  // 运营位不与自然列表重复曝光同一作品（列表已在场时精选卡让位）。
  const featuredShown = featured.filter(
    (item) => !state.works.some((work) => work.workId === item.workId),
  );

  return (
    <div>
      {submittedQuery === null && featuredShown.length > 0 ? (
        <div className="catalog-featured" role="region" aria-label="运营推荐">
          <h2 className="catalog-featured__title">运营推荐</h2>
          <ul className="catalog-list" aria-label="运营推荐作品">
            {featuredShown.map((item) => {
              const blocked = BAND_RANK[gradeBand(item.grade)] > BAND_RANK[ageBand];
              return (
                <li key={'featured-' + item.workId}>
                  <button
                    className="catalog-book"
                    type="button"
                    disabled={blocked}
                    onClick={() => onOpenDetail(item.workId)}
                  >
                    <span className="catalog-book__main">
                      <span className="catalog-book__title">{item.title}</span>
                      <span className="catalog-book__author">{item.author}</span>
                    </span>
                    <span className="catalog-book__detail">
                      {blocked ? '当前年龄带暂不可读'
                        : `运营推荐 · ${EXPOSURE_LABEL[item.exposureReason] ?? item.exposureReason}`}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}
      {state.works.length === 0 ? (
        <div className="catalog-state">
          {submittedQuery === null ? '暂无作品' : `没有找到与「${submittedQuery}」相关的作品`}
        </div>
      ) : (
        <ul className="catalog-list" aria-label={submittedQuery === null ? '作品' : '搜索结果'}>
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
      )}
      {submittedQuery !== null && state.nextCursor !== null ? (
        <div className="catalog-state">
          <button className="btn" type="button" disabled={loadingMore} onClick={loadMore}>
            {loadingMore ? '正在加载' : '加载更多'}
          </button>
        </div>
      ) : null}
    </div>
  );
}

/** 搜索分页去重合并（已有条目按 workId 保留，新页条目追加在后）。 */
function dedupeWorks(existing: WorkListItemDto[], incoming: WorkListItemDto[]): WorkListItemDto[] {
  const seen = new Set(existing.map((work) => work.workId));
  return [...existing, ...incoming.filter((work) => !seen.has(work.workId))];
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
