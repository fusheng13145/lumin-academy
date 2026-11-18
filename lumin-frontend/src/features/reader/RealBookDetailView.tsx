import { useEffect, useState } from 'react';
import { BookDetailView } from './BookDetailView';
import type { BookDetailState } from './BookDetailView';
import { readerService } from '../../services/readerService';
import { realBook } from '../../lib/realBook';
import type { AgeBand } from '../../types';

interface RealBookDetailViewProps {
  workId: string;
  index: number;
  ageBand: AgeBand;
  onBack: () => void;
  onRead: (workId: string, chapterIdx: number) => void;
}

export function RealBookDetailView(props: RealBookDetailViewProps) {
  return <DetailRequest key={`${props.workId}-${props.ageBand}`} {...props} />;
}

function DetailRequest({ workId, index, ageBand, onBack, onRead }: RealBookDetailViewProps) {
  const [state, setState] = useState<BookDetailState>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const detail = await readerService.getWork(workId, ageBand);
        if (!alive) return;
        const chapters = await readerService.listChapters(workId, ageBand);
        if (alive) {
          setState({ status: 'ready', book: realBook(detail, chapters, index) });
        }
      } catch {
        if (alive) {
          setState({ status: 'error', onRetry: () => {
            setState({ status: 'loading' });
            setAttempt((previous) => previous + 1);
          } });
        }
      }
    })();
    return () => { alive = false; };
  }, [workId, ageBand, index, attempt]);

  return <BookDetailView state={state} ageBand={ageBand} onBack={onBack} onRead={onRead} />;
}
