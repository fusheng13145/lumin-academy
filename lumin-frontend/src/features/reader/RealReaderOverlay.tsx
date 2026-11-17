import { useEffect, useState } from 'react';
import { ReaderOverlay } from './ReaderOverlay';
import { readerService } from '../../services/readerService';
import type { ChapterEntryDto, ContentDto } from '../../services/readerService';
import { realBook } from '../../lib/realBook';
import type { AgeBand, Book } from '../../types';

interface RealReaderOverlayProps {
  workId: string;
  chapterIdx: number;
  index: number;
  ageBand: AgeBand;
  uiTheme: 'light' | 'night';
  onToggleUiTheme: () => void;
  announce: (message: string) => void;
  onChapterChange: (chapterIdx: number) => void;
  onClose: () => void;
}

// R03 真实数据路径：章节目录 + 正文来自读者链 API；排版工具栏复用演示组件。
export function RealReaderOverlay({
  workId,
  chapterIdx,
  index,
  ageBand,
  uiTheme,
  onToggleUiTheme,
  announce,
  onChapterChange,
  onClose,
}: RealReaderOverlayProps) {
  const [chapters, setChapters] = useState<ChapterEntryDto[] | null>(null);
  const [content, setContent] = useState<ContentDto | null>(null);
  const [detail, setDetail] = useState<Awaited<ReturnType<typeof readerService.getWork>> | null>(null);
  const [error, setError] = useState<string | null>(null);

  // 目录与作品元数据：进入阅读器时获取一次。
  useEffect(() => {
    let alive = true;
    void (async () => {
      try {
        const [list, work] = await Promise.all([
          readerService.listChapters(workId, ageBand),
          readerService.getWork(workId, ageBand),
        ]);
        if (alive) {
          setChapters(list);
          setDetail(work);
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

  // 正文：随章号变化拉取对应已发布版本；切换期间保留上一章正文（阅读连续性）。
  useEffect(() => {
    if (!chapters || chapters.length === 0) {
      return;
    }
    const idx = Math.min(Math.max(chapterIdx, 0), chapters.length - 1);
    let alive = true;
    void (async () => {
      try {
        const body = await readerService.readChapter(chapters[idx].id, ageBand);
        if (alive) {
          setContent(body);
          setError(null);
          // 版本变更提示（T29）：已有进度绑定旧版本时如实告知，位置不跨版本套用
          const progress = await readerService.getProgress(chapters[idx].id, ageBand);
          if (alive && progress && progress.versionId !== body.versionId) {
            announce('该章节已更新至新版本，之前保存的阅读位置属于旧版本。');
          }
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
  }, [chapters, chapterIdx, ageBand, announce]);

  if (!chapters || !detail) {
    return (
      <div className="reader-overlay" role="dialog" aria-modal="true" aria-label="阅读">
        <div className="reader-topbar">
          <button className="btn btn--ghost" type="button" onClick={onClose}>返回</button>
          <span className="reader-topbar__title">加载中</span>
        </div>
        <div className="reader-shell">
          <div className="reader-stage">
            <div className="reader-paper">
              {error ? <p className="muted-note">{error}</p> : <div className="empty">正在获取章节</div>}
            </div>
          </div>
        </div>
      </div>
    );
  }

  const book: Book = realBook(detail, chapters, index);
  return (
    <ReaderOverlay
      book={book}
      chapterIdx={chapterIdx}
      ageBand={ageBand}
      uiTheme={uiTheme}
      onToggleUiTheme={onToggleUiTheme}
      announce={announce}
      onChapterChange={onChapterChange}
      onClose={onClose}
      realBody={content?.body ?? null}
      realTitle={content?.title ?? null}
      realError={error}
    />
  );
}
