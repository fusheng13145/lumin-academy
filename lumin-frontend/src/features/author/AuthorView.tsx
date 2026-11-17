import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../../components/icons/Icon';
import type { MyWork } from '../../services/workService';
import { createWork, listMyWorks } from '../../services/workService';
import { draftService } from '../../services/draftService';

// 创作：我的作品（真实数据，经替身身份）
export function AuthorView() {
  const [works, setWorks] = useState<MyWork[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setError(null);
    try {
      setWorks(await listMyWorks());
    } catch (e) {
      setWorks([]);
      setError(e instanceof Error ? e.message : '加载失败');
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const createNewWork = async (): Promise<void> => {
    setError(null);
    try {
      await createWork();
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : '创建失败');
    }
  };

  const submitLatest = async (workId: string): Promise<void> => {
    setError(null);
    try {
      // 最小演示流：作品下建一个默认草稿并提交（幂等键由 http 层生成）
      const draft = await draftService.createDraft({
        workId,
        title: '未命名草稿',
        synopsis: ' ',
      });
      await draftService.submitDraft(draft.id, String(draft.revision));
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : '提交失败');
    }
  };

  return (
    <section aria-labelledby="author-h">
      <header className="page-head">
        <h1 id="author-h" tabIndex={-1}>创作</h1>
      </header>

      <div className="toolbar">
        <button className="btn btn--primary" type="button" onClick={() => void createNewWork()}>
          新建作品
        </button>
      </div>

      {error && <p className="muted-note">{error}</p>}

      {works === null ? null : works.length === 0 ? (
        <div className="empty">还没有作品，点击「新建作品」开始</div>
      ) : (
        <ul className="draft-list">
          {works.map((w) => (
            <li key={w.id} className="draft-item card">
              <div className="draft-item__main">
                <h3>{w.latestTitle ?? '（无版本）'}</h3>
                <p className="draft-item__meta">
                  {w.businessState} · 最新版本 {w.latestVersionState ?? '—'}
                  {w.draftState ? ` · 草稿 ${w.draftState}` : ''}
                </p>
              </div>
              {w.draftState === 'EDITABLE' && w.draftId && (
                <button
                  className="btn btn--soft"
                  type="button"
                  onClick={() => void submitLatest(w.id)}
                >
                  <Icon name="review" size={16} /> 提交审校
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
