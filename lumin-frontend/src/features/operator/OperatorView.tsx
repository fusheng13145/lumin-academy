import { useCallback, useEffect, useState } from 'react';
import { configService, type ConfigRevisionDto } from '../../services/configService';

const KEYS = ['REVIEW_ROUTING', 'ACCESS_POLICY', 'NOTIFICATION_POLICY', 'CONTENT_CATALOG'] as const;

// 运营与配置：配置修订链（提案 OPERATOR → 独立 ADMIN 审批 → 激活/回滚；真实 API）
export function OperatorView() {
  const [revisions, setRevisions] = useState<ConfigRevisionDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [draftKey, setDraftKey] = useState<string>('REVIEW_ROUTING');
  const [draftPayload, setDraftPayload] = useState('');
  const [draftReason, setDraftReason] = useState('');

  const reload = useCallback(async (): Promise<void> => {
    setError(null);
    try {
      setRevisions(await configService.listConfigRevisions());
    } catch (e) {
      setRevisions([]);
      setError(e instanceof Error ? e.message : '加载失败');
    }
  }, []);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const list = await configService.listConfigRevisions();
        if (alive) {
          setRevisions(list);
        }
      } catch (e) {
        if (alive) {
          setRevisions([]);
          setError(e instanceof Error ? e.message : '加载失败');
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, []);

  const propose = async (): Promise<void> => {
    setError(null);
    setBusy(true);
    try {
      await configService.proposeConfigRevision(draftKey, draftPayload || '{}', draftReason);
      setDraftPayload('');
      setDraftReason('');
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : '提案失败');
    } finally {
      setBusy(false);
    }
  };

  const review = async (item: ConfigRevisionDto, decision: 'APPROVE' | 'REJECT'): Promise<void> => {
    setError(null);
    setBusy(true);
    try {
      await configService.reviewConfigRevision(item.id, item.lockVersion, decision,
        decision === 'APPROVE');
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : '审批失败');
    } finally {
      setBusy(false);
    }
  };

  const activate = async (item: ConfigRevisionDto): Promise<void> => {
    setError(null);
    setBusy(true);
    try {
      await configService.activateConfigRevision(item.id);
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : '激活失败');
    } finally {
      setBusy(false);
    }
  };

  const rollback = async (item: ConfigRevisionDto): Promise<void> => {
    setError(null);
    setBusy(true);
    try {
      await configService.proposeConfigRevision(item.configKey, '', `回滚到 r${item.revision}`,
        item.id);
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : '回滚提案失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="operator-h">
      <header className="page-head">
        <h1 id="operator-h" tabIndex={-1}>运营与配置</h1>
      </header>

      <div className="card setting-block">
        <h2 className="setting-block__title">新建提案</h2>
        <div className="toolbar">
          <select className="select" aria-label="配置键" value={draftKey}
            onChange={(e) => setDraftKey(e.target.value)}>
            {KEYS.map((key) => <option key={key} value={key}>{key}</option>)}
          </select>
          <input className="input" aria-label="提案原因" placeholder="原因" value={draftReason}
            onChange={(e) => setDraftReason(e.target.value)} />
        </div>
        <textarea aria-label="提案 payload（JSON）" rows={3} value={draftPayload}
          onChange={(e) => setDraftPayload(e.target.value)}
          placeholder='{"minAutoConfidenceBp":9900,"sampleRateBp":500,"minorProtectionEnabled":true}'
          style={{ width: '100%', fontFamily: 'inherit', fontSize: 'inherit' }} />
        <div className="toolbar">
          <button className="btn btn--primary" type="button" disabled={busy || draftReason.trim() === ''}
            onClick={() => void propose()}>
            提交提案
          </button>
        </div>
      </div>

      {error && <p className="muted-note">{error}</p>}

      {revisions === null ? null : revisions.length === 0 ? (
        <div className="empty">暂无配置修订</div>
      ) : (
        <ul className="draft-list">
          {revisions.map((item) => (
            <li key={item.id} className="draft-item card">
              <div className="draft-item__main">
                <h3>{item.configKey} · r{item.revision} · {item.state}</h3>
                <p className="draft-item__meta">
                  {item.rolloutBasisPoints === 10000 ? '全量' : `灰度 ${item.rolloutBasisPoints / 100}%`}
                  {item.rollbackOf ? ' · 回滚' : ''} · {item.reason}
                </p>
                <p className="draft-item__meta">{item.payload}</p>
              </div>
              <div className="btn-row">
                {item.state === 'IN_REVIEW' && (
                  <>
                    <button className="btn btn--soft" type="button" disabled={busy}
                      onClick={() => void review(item, 'APPROVE')}>
                      审批通过
                    </button>
                    <button className="btn btn--soft" type="button" disabled={busy}
                      onClick={() => void review(item, 'REJECT')}>
                      驳回
                    </button>
                  </>
                )}
                {item.state === 'APPROVED' && (
                  <button className="btn btn--primary" type="button" disabled={busy}
                    onClick={() => void activate(item)}>
                    激活
                  </button>
                )}
                {item.state === 'ACTIVE' && (
                  <button className="btn btn--soft" type="button" disabled={busy}
                    onClick={() => void rollback(item)}>
                    回滚到此版
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
