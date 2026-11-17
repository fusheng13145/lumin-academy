import { useCallback, useEffect, useState } from 'react';
import { Icon } from '../../components/icons/Icon';
import type { MyWork } from '../../services/workService';
import { createWork, listMyWorks } from '../../services/workService';
import { createDraft, submitDraft } from '../../services/draftService';
import { chapterService, type MyChapter } from '../../services/chapterService';

// 创作：作品列表 → 章节 → 章节草稿 → 提交审校（真实 API）
export function AuthorView() {
  const [works, setWorks] = useState<MyWork[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [chapters, setChapters] = useState<MyChapter[] | null>(null);
  const [chapterBody, setChapterBody] = useState('');
  const [busy, setBusy] = useState(false);

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

  const select = async (work: MyWork): Promise<void> => {
    if (selectedId === work.id) {
      setSelectedId(null);
      setChapters(null);
      return;
    }
    setSelectedId(work.id);
    setChapters(null);
    try {
      setChapters(await chapterService.listMyChapters(work.id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '章节加载失败');
    }
  };

  const createNewChapter = async (work: MyWork): Promise<void> => {
    setError(null);
    setBusy(true);
    try {
      const { chapterService: svc } = await import('../../services/chapterService');
      await svc.createChapter(work.id);
      setChapters(await chapterService.listMyChapters(work.id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '新建章节失败');
    } finally {
      setBusy(false);
    }
  };

  const createAndSubmitChapterDraft = async (work: MyWork, chapter: MyChapter): Promise<void> => {
    setError(null);
    setBusy(true);
    try {
      const draft = await createDraft({
        workId: work.id,
        chapterId: chapter.id,
        title: `第 ${chapter.sequenceNo} 章`,
        body: chapterBody,
      });
      await submitDraft(draft.id, String(draft.revision));
      setChapterBody('');
      setChapters(await chapterService.listMyChapters(work.id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '提交失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="author-h">
      <header className="page-head">
        <h1 id="author-h" tabIndex={-1}>创作</h1>
      </header>

      <div className="toolbar">
        <button className="btn btn--primary" type="button" disabled={busy} onClick={() => void createNewWork()}>
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
                  onClick={() => void select(w)}
                >
                  <Icon name="review" size={16} /> 写章节
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {selectedId && chapters !== null && (
        <div className="card setting-block" style={{ marginTop: 'var(--sp-5)' }}>
          <h2 className="setting-block__title">章节</h2>
          {chapters.map((ch) => (
            <div key={ch.id} className="setting-row">
              <span>第 {ch.sequenceNo} 章 · {ch.businessState}
                {ch.draftState ? ` · 草稿 ${ch.draftState}` : ''}</span>
            </div>
          ))}
          <div className="toolbar">
            <button className="btn btn--soft" type="button" disabled={busy}
              onClick={() => { const w = (works ?? []).find((w) => w.id === selectedId); if (w) void createNewChapter(w); }}>
              新建章节
            </button>
          </div>
          {chapters.length > 0 && (
            <div>
              <textarea
                aria-label="章节草稿正文"
                rows={8}
                style={{ width: '100%', fontFamily: 'inherit', fontSize: 'inherit' }}
                value={chapterBody}
                onChange={(e) => setChapterBody(e.target.value)}
                placeholder="章节正文"
              />
              <div className="toolbar">
                <button className="btn btn--primary" type="button" disabled={busy || chapterBody.trim() === ''}
                  onClick={() => { const w = (works ?? []).find((w) => w.id === selectedId); const ch = chapters[0]; if (w && ch) void createAndSubmitChapterDraft(w, ch); }}>
                  提交章节审校
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
