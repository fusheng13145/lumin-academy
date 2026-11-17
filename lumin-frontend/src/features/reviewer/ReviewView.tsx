import { useCallback, useEffect, useState } from 'react';
import type { ReviewCase } from '../../services/caseService';
import { caseService } from '../../services/caseService';

// 审核：裁决队列（替身审核员 REVIEWER_L1A）
export function ReviewView() {
  const [cases, setCases] = useState<ReviewCase[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      setCases(await caseService.listCases());
    } catch (e) {
      setCases([]);
      setError(e instanceof Error ? e.message : '加载失败');
    }
  }, []);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const list = await caseService.listCases();
        if (alive) {
          setCases(list);
        }
      } catch (e) {
        if (alive) {
          setCases([]);
          setError(e instanceof Error ? e.message : '加载失败');
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const claim = async (id: string): Promise<void> => {
    setError(null);
    try {
      await caseService.claimCase(id, '1');
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : '领取失败');
    }
  };

  const decide = async (id: string): Promise<void> => {
    setError(null);
    try {
      await caseService.decideCase(id, '2', {
        decision: 'PASS',
        grade: 'G',
        reasonCode: 'OK_1001',
        reason: '内容合规',
      });
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : '裁决失败');
    }
  };

  return (
    <section aria-labelledby="review-h">
      <header className="page-head">
        <h1 id="review-h" tabIndex={-1}>审核</h1>
      </header>

      {error && <p className="muted-note">{error}</p>}

      {cases === null ? null : cases.length === 0 ? (
        <div className="empty">队列为空</div>
      ) : (
        <ul className="review-list">
          {cases.map((c) => (
            <li key={c.id} className="review-item card">
              <div className="review-item__head">
                <h3>{c.workId.slice(0, 8)}…</h3>
                <span className="badge badge--review">{c.risk} · {c.priority}</span>
              </div>
              <p className="review-item__meta">阶段 {c.stage} · {c.state}</p>
              {c.state === 'QUEUED' && (
                <button className="btn btn--soft" type="button" onClick={() => void claim(c.id)}>
                  领取
                </button>
              )}
              {c.state === 'CLAIMED' && (
                <button className="btn btn--primary" type="button" onClick={() => void decide(c.id)}>
                  通过 (G)
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
