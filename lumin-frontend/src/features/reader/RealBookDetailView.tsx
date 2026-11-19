import { useEffect, useState } from 'react';
import { BookDetailView } from './BookDetailView';
import type { BookDetailState } from './BookDetailView';
import { readerService } from '../../services/readerService';
import { subscriptionService } from '../../services/subscriptionService';
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
  // 追更订阅（UGC-036 收口）：详情页即订阅入口——状态/切换/失败如实呈现
  const [subscribed, setSubscribed] = useState(false);
  const [subBusy, setSubBusy] = useState(false);
  const [subError, setSubError] = useState<string | null>(null);

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

  // 订阅状态：进详情页拉取一次；失败静默（按钮按未订阅呈现，不阻塞阅读）
  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const isSubscribed = await subscriptionService.subscriptionStatus(workId);
        if (alive) {
          setSubscribed(isSubscribed);
        }
      } catch {
        if (alive) {
          setSubscribed(false);
        }
      }
    })();
    return () => { alive = false; };
  }, [workId]);

  const toggleSubscription = async (): Promise<void> => {
    setSubBusy(true);
    setSubError(null);
    try {
      if (subscribed) {
        await subscriptionService.unsubscribe(workId);
        setSubscribed(false);
      } else {
        await subscriptionService.subscribe(workId);
        setSubscribed(true);
      }
    } catch (e) {
      setSubError(e instanceof Error ? e.message : '追更操作未成功，请稍后重试');
    } finally {
      setSubBusy(false);
    }
  };

  return (
    <BookDetailView
      state={state}
      ageBand={ageBand}
      onBack={onBack}
      onRead={onRead}
      subscription={{
        subscribed,
        busy: subBusy,
        error: subError,
        onToggle: () => void toggleSubscription(),
      }}
    />
  );
}
