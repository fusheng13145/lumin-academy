import { useEffect, useState } from 'react';
import { BookDetailView } from './BookDetailView';
import { readerService } from '../../services/readerService';
import type { WorkDetailDto } from '../../services/readerService';
import { realBook } from '../../lib/realBook';
import type { AgeBand, Book } from '../../types';

interface RealBookDetailViewProps {
  workId: string;
  index: number;
  ageBand: AgeBand;
  onBack: () => void;
  onRead: (workId: string, chapterIdx: number) => void;
}

interface Loaded {
  detail: WorkDetailDto;
  chapters: { id: string; sequenceNo: number; businessState: string }[];
}

// R02 真实数据路径：作品详情 + 章节目录来自读者链 API；排版复用演示组件。
// 适龄门禁真实权威在后端（未授权 404）；前端灰显用 grade→band 派生口径。
export function RealBookDetailView({ workId, index, ageBand, onBack, onRead }: RealBookDetailViewProps) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const detail = await readerService.getWork(workId, ageBand);
        const chapters = await readerService.listChapters(workId, ageBand);
        if (alive) {
          setLoaded({ detail, chapters });
        }
      } catch (e) {
        if (alive) {
          setError(e instanceof Error ? e.message : '加载失败');
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, [workId, ageBand]);

  if (error) {
    return (
      <section aria-labelledby="detail-real-h">
        <header className="page-head">
          <h1 id="detail-real-h" tabIndex={-1}>作品不可读</h1>
        </header>
        <p className="muted-note">{error}</p>
        <div className="btn-row">
          <button className="btn btn--soft" type="button" onClick={onBack}>返回书城</button>
        </div>
      </section>
    );
  }

  if (!loaded) {
    return (
      <section aria-labelledby="detail-real-h">
        <header className="page-head">
          <h1 id="detail-real-h" tabIndex={-1}>加载中</h1>
        </header>
        <div className="empty">正在获取作品</div>
      </section>
    );
  }

  const book: Book = realBook(loaded.detail, loaded.chapters, index);
  return (
    <BookDetailView book={book} ageBand={ageBand} onBack={onBack} onRead={onRead} />
  );
}
