import { useMemo, useState } from 'react';
import { MOBILE_BOOKS } from '../data/library';
import { TopBar } from '../components/ui';
import { IconSearch } from '../components/Icons';

interface SearchScreenProps {
  onBack: () => void;
  onOpenBook: (bookId: string) => void;
}

const SUGGESTIONS = ['潮汐', '机关', '旧信', '校园', '近未来'];

export function SearchScreen({ onBack, onOpenBook }: SearchScreenProps) {
  const [query, setQuery] = useState('');

  const results = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) {
      return MOBILE_BOOKS;
    }
    return MOBILE_BOOKS.filter((book) =>
      [book.title, book.author, book.category, book.summary, ...book.tags]
        .join(' ')
        .toLowerCase()
        .includes(keyword)
    );
  }, [query]);

  return (
    <div className="m-page m-enter">
      <TopBar title="搜索" subtitle="FIND A STORY" onBack={onBack} />
      <div className="m-search" style={{ marginTop: 12 }}>
        <IconSearch />
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="书名、作者、题材或关键词"
          aria-label="搜索作品"
          autoFocus
        />
      </div>

      <div className="m-memory" style={{ marginTop: 14 }}>
        {SUGGESTIONS.map((word) => (
          <button
            key={word}
            className="m-chip"
            type="button"
            onClick={() => setQuery(word)}
            aria-pressed={query === word}
          >
            {word}
          </button>
        ))}
      </div>

      <p className="m-caption" style={{ marginTop: 18 }}>
        {query ? `${results.length} 个结果` : '全部作品'} · 本地检索
      </p>

      {results.length === 0 ? (
        <div className="m-empty" style={{ marginTop: 16 }}>
          <p className="m-h3">没有找到相关作品</p>
          <p className="m-caption" style={{ marginTop: 6 }}>
            试试更短的关键词，或从书城首页浏览。
          </p>
        </div>
      ) : (
        <div className="m-result-list">
          {results.map((book) => (
            <button
              key={book.id}
              className="m-result"
              type="button"
              onClick={() => onOpenBook(book.id)}
              style={{ borderInline: 0, borderTop: 0, background: 'transparent', color: 'inherit', textAlign: 'left' }}
            >
              <div
                style={{
                  width: 64,
                  height: 84,
                  borderRadius: 9,
                  background: book.cover[0],
                  color: book.cover[1],
                  display: 'grid',
                  placeItems: 'center',
                  fontFamily: 'var(--m-font-serif)',
                  fontSize: 24,
                  fontWeight: 700,
                  flex: 'none',
                }}
                aria-hidden="true"
              >
                {book.title.slice(0, 1)}
              </div>
              <div>
                <p className="m-result__title">{book.title}</p>
                <p className="m-result__meta">
                  {book.author} · {book.category} · {book.stats.reads} 阅读
                </p>
                <p className="m-caption" style={{ marginTop: 5 }}>
                  {book.summary}
                </p>
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
