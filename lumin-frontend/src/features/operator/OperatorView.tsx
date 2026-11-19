import { useCallback, useEffect, useState } from 'react';
import { configService, type ConfigRevisionDto } from '../../services/configService';
import { curationService, EXPOSURE_LABEL, EXPOSURE_REASONS,
  type CurationItemDto } from '../../services/curationService';
import { metricsService, type ReadingMetricsDto, type RetentionMetricsDto,
  type SupplyMetricsDto } from '../../services/metricsService';

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

      <CurationBoard />
      <MetricsBoard />
    </section>
  );
}

// 运营精选工作台（UGC-039 二阶段）：提案（OPERATOR 身份）→ 独立审批/撤位
// （ADMIN 身份）→ 书城精选位即时生效。曝光原因字典化、干预显式化。
function CurationBoard() {
  const [items, setItems] = useState<CurationItemDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [workId, setWorkId] = useState('');
  const [reason, setReason] = useState<string>('FEATURED');
  const [note, setNote] = useState('');

  const reload = useCallback(async (): Promise<void> => {
    try {
      setItems(await curationService.listCurationItems());
      setError(null);
    } catch (e) {
      setItems([]);
      setError(e instanceof Error ? e.message : '加载失败');
    }
  }, []);

  // 挂载加载内联异步（effect 体内同步 setState 触发级联规则）
  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const list = await curationService.listCurationItems();
        if (alive) {
          setItems(list);
          setError(null);
        }
      } catch (e) {
        if (alive) {
          setItems([]);
          setError(e instanceof Error ? e.message : '加载失败');
        }
      }
    })();
    return () => { alive = false; };
  }, []);

  const propose = async (): Promise<void> => {
    setBusy(true);
    setError(null);
    try {
      await curationService.proposeCurationItem(workId.trim(), reason, note.trim());
      setWorkId('');
      setNote('');
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : '提案失败');
    } finally {
      setBusy(false);
    }
  };

  const review = async (id: string, decision: 'APPROVE' | 'REJECT'): Promise<void> => {
    setBusy(true);
    setError(null);
    try {
      await curationService.reviewCurationItem(id, decision);
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : '审批失败');
    } finally {
      setBusy(false);
    }
  };

  const retire = async (id: string): Promise<void> => {
    setBusy(true);
    setError(null);
    try {
      await curationService.retireCurationItem(id);
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : '撤位失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card setting-block" aria-label="运营精选">
      <h2 className="setting-block__title">运营精选（曝光干预 · 显式标注）</h2>
      <div className="toolbar">
        <input
          className="input"
          aria-label="作品 ID"
          placeholder="已发布作品的 ID"
          value={workId}
          onChange={(e) => setWorkId(e.target.value)}
        />
        <select className="select" aria-label="曝光原因" value={reason}
          onChange={(e) => setReason(e.target.value)}>
          {EXPOSURE_REASONS.map((key) => (
            <option key={key} value={key}>{EXPOSURE_LABEL[key]}</option>
          ))}
        </select>
        <input
          className="input"
          aria-label="运营备注"
          placeholder="备注（可选，不向读者展示）"
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />
        <button
          className="btn btn--primary"
          type="button"
          disabled={busy || workId.trim() === ''}
          onClick={() => void propose()}
        >
          提交精选提案
        </button>
      </div>

      {error && <p className="muted-note" role="alert">{error}</p>}

      {items === null ? null : items.length === 0 ? (
        <div className="empty">暂无精选提案</div>
      ) : (
        <ul className="draft-list">
          {items.map((item) => (
            <li key={item.id} className="draft-item card">
              <div className="draft-item__main">
                <h3>
                  {`${item.workId.slice(0, 8)}… · ${EXPOSURE_LABEL[item.exposureReason]
                    ?? item.exposureReason} · ${item.state}`}
                </h3>
                <p className="draft-item__meta">
                  规则版本 r{item.ruleVersion} · 提案 {item.proposedBy.slice(0, 8)}…
                  {item.reviewedBy !== '' ? ` · 审批 ${item.reviewedBy.slice(0, 8)}…` : ''}
                  {item.note !== '' ? ` · 备注：${item.note}` : ''}
                </p>
              </div>
              <div className="btn-row">
                {item.state === 'PENDING' && (
                  <>
                    <button className="btn btn--soft" type="button" disabled={busy}
                      onClick={() => void review(item.id, 'APPROVE')}>
                      审批生效
                    </button>
                    <button className="btn btn--soft" type="button" disabled={busy}
                      onClick={() => void review(item.id, 'REJECT')}>
                      驳回
                    </button>
                  </>
                )}
                {item.state === 'ACTIVE' && (
                  <button className="btn btn--soft" type="button" disabled={busy}
                    onClick={() => void retire(item.id)}>
                    撤位
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// 可信看板（UGC-040）：读数带口径版本；空数据如实呈现「暂无数据」，不编数字。
const RATE_LABEL = (rate: number): string => `${Math.round(rate * 100)}%`;

function MetricsBoard() {
  const [reading, setReading] = useState<ReadingMetricsDto | null>(null);
  const [retention, setRetention] = useState<RetentionMetricsDto | null>(null);
  const [supply, setSupply] = useState<SupplyMetricsDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [r, ret, s] = await Promise.all([
          metricsService.listReadingMetrics(),
          metricsService.listRetentionMetrics(),
          metricsService.listSupplyMetrics(),
        ]);
        if (alive) {
          setReading(r);
          setRetention(ret);
          setSupply(s);
        }
      } catch (e) {
        if (alive) {
          setError(e instanceof Error ? e.message : '加载失败');
        }
      }
    })();
    return () => { alive = false; };
  }, []);

  if (error) {
    return <p className="muted-note" role="alert">看板加载失败：{error}</p>;
  }
  if (reading === null || retention === null || supply === null) {
    return <div className="catalog-state" aria-busy="true">正在加载看板</div>;
  }
  return (
    <div className="card setting-block" aria-label="供给与阅读看板">
      <h2 className="setting-block__title">
        供给与阅读看板（口径 v{reading.frozenDefinitionVersion}）
      </h2>
      <p className="draft-item__meta">
        有效阅读（近 {reading.windowDays} 天，去重读者×章节）：
        <strong>{reading.effectiveReads}</strong>
        {reading.syntheticExcluded > 0
          ? ` · 已剔除合成流量 ${reading.syntheticExcluded}` : ''}
      </p>
      {reading.byBand.length > 0 ? (
        <p className="draft-item__meta">
          分群：{reading.byBand.map((band) => `${band.band} ${band.reads}`).join(' · ')}
        </p>
      ) : (
        <p className="draft-item__meta">分群：暂无数据（无数据不编数字）</p>
      )}
      <p className="draft-item__meta">
        次周留存：{retention.cohorts.length === 0
          ? '暂无分群数据（无数据不编数字）'
          : retention.cohorts.map((cohort) =>
              `${cohort.weekStart} 群 ${cohort.cohortSize} 留存 ${cohort.retainedCount}`
              + `（${RATE_LABEL(cohort.retentionRate)}）`).join(' · ')}
      </p>
      <p className="draft-item__meta">
        供给健康：提交 <strong>{supply.submissions}</strong> · 通过 {supply.approved}
        {' '}· 驳回 {supply.rejected} · 生效下架 {supply.takedownActive}
        {' '}· 生效精选 {supply.featuredActive}
      </p>
      <p className="draft-item__meta">
        读者档案：真实 {supply.realReaders} · 合成（已剔除）{supply.syntheticProfiles}
      </p>
    </div>
  );
}
