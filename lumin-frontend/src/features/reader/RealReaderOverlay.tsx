import { useCallback, useEffect, useRef, useState } from 'react';
import { ReaderOverlay } from './ReaderOverlay';
import { readerService } from '../../services/readerService';
import type { ChapterEntryDto, ContentDto, SaveProgressPayload } from '../../services/readerService';
import { ApiError } from '../../services/http';
import { canRestore, cumulativeReadSeconds, shouldSave } from '../../lib/readingProgressSync';
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
// UGC-016 进度保存链：滚动 → 万分比 → 防抖 PUT（If-Match 乐观锁，首写 "0"）；
// 冲突重取再试；失败保留本机位置不伪称同步；切账号（年龄带切换）即清空同步态。
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
  const [initialScrollRatio, setInitialScrollRatio] = useState<number | null>(null);

  // —— 进度同步状态（ref 直存，避免滚动重渲染）——
  const etagRef = useRef<string>('0');
  const positionRef = useRef<number>(0);
  const savedBpRef = useRef<number | null>(null);
  const readStartRef = useRef<number>(0);
  const prevReadSecondsRef = useRef<number>(0);
  const saveTimerRef = useRef<number | null>(null);
  const syncWarnedRef = useRef(false);
  const currentChapterRef = useRef<ChapterEntryDto | null>(null);
  const contentRef = useRef<ContentDto | null>(null);
  const flushSaveRef = useRef<(chapterId: string, force: boolean) => Promise<void>>(async () => {});

  /** 保存（If-Match 乐观锁；冲突重取后重试一次；失败保留本机不伪称同步）。 */
  const flushSave = useCallback(async (chapterId: string, force: boolean) => {
    const bp = positionRef.current;
    if (shouldSave(savedBpRef.current, bp, force) === 'skip') {
      return;
    }
    const sessionDelta = Math.round((Date.now() - readStartRef.current) / 1000);
    const payload: SaveProgressPayload = {
      positionBasisPoints: bp,
      readSeconds: cumulativeReadSeconds(prevReadSecondsRef.current, sessionDelta),
      clientUpdatedAt: new Date().toISOString(),
    };
    try {
      const result = await readerService.saveProgress(
          chapterId, payload, `"${etagRef.current}"`, ageBand);
      etagRef.current = result.etag;
      savedBpRef.current = bp;
      prevReadSecondsRef.current = payload.readSeconds;
      if (result.versionChanged) {
        announce('本章已更新至新版本，阅读位置已重绑至当前版本。');
      }
    } catch (e) {
      if (e instanceof ApiError && (e.code === 'B2002' || e.code === 'P1002')) {
        // 乐观锁冲突：另一设备/会话已保存——以服务器最新版本号为准重试一次
        try {
          const latest = await readerService.getProgress(chapterId, ageBand);
          etagRef.current = latest ? latest.etag : '0';
          prevReadSecondsRef.current = latest ? latest.readSeconds : 0;
          const retry = await readerService.saveProgress(
              chapterId, payload, `"${etagRef.current}"`, ageBand);
          etagRef.current = retry.etag;
          savedBpRef.current = bp;
          if (retry.versionChanged) {
            announce('本章已更新至新版本，阅读位置已重绑至当前版本。');
          }
          return;
        } catch {
          // 重试仍失败 → 落入失败保留
        }
      }
      // 保留本机位置（positionRef 不动）；提示每次会话仅一次，不阻塞阅读
      if (!syncWarnedRef.current) {
        syncWarnedRef.current = true;
        announce('阅读进度暂时未能同步，已保留在本机，继续阅读稍后会自动重试。');
      }
    }
  }, [ageBand, announce]);

  // 最新保存函数经 effect 同步进 ref：render 期不写 ref（react-compiler 规则）。
  useEffect(() => {
    flushSaveRef.current = flushSave;
  }, [flushSave]);

  /** 滚动回调：更新位置 + 3 秒防抖保存（已有待触发的保存则不重复排队）。 */
  const handleScrollRatio = useCallback((bp: number) => {
    positionRef.current = bp;
    if (saveTimerRef.current !== null) {
      return;
    }
    saveTimerRef.current = window.setTimeout(() => {
      saveTimerRef.current = null;
      const chapter = currentChapterRef.current;
      if (chapter && contentRef.current && contentRef.current.chapterId === chapter.id) {
        void flushSaveRef.current(chapter.id, false);
      }
    }, 3000);
  }, []);

  // 章节切换 / 关闭 / 账号（年龄带）切换：兜底落盘当前位置，随后清空同步态
  // （etag 复位为首写 "0"；服务端按主体隔离，切账号不会读到他人进度）。
  useEffect(() => {
    return () => {
      if (saveTimerRef.current !== null) {
        window.clearTimeout(saveTimerRef.current);
        saveTimerRef.current = null;
      }
      const chapter = currentChapterRef.current;
      if (chapter && contentRef.current && contentRef.current.chapterId === chapter.id) {
        void flushSaveRef.current(chapter.id, true);
      }
      etagRef.current = '0';
      savedBpRef.current = null;
      positionRef.current = 0;
      prevReadSecondsRef.current = 0;
      readStartRef.current = Date.now();
      setInitialScrollRatio(null);
    };
  }, [chapterIdx, ageBand]);

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
          currentChapterRef.current = chapters[idx];
          contentRef.current = body;
          readStartRef.current = Date.now();
          savedBpRef.current = null;
          // 版本变更提示（T29）+ 同版本一次性恢复位置（UGC-016 跨端一致性）
          const progress = await readerService.getProgress(chapters[idx].id, ageBand);
          if (!alive) {
            return;
          }
          if (progress) {
            etagRef.current = progress.etag;
            prevReadSecondsRef.current = progress.readSeconds;
          } else {
            etagRef.current = '0';
            prevReadSecondsRef.current = 0;
          }
          if (progress && progress.versionId !== body.versionId) {
            // 已有进度绑定旧版本：如实告知，位置不跨版本套用
            announce('该章节已更新至新版本，之前保存的阅读位置属于旧版本。');
          } else if (canRestore(progress?.versionId, body.versionId)
              && (progress?.positionBasisPoints ?? 0) > 0) {
            setInitialScrollRatio((progress?.positionBasisPoints ?? 0) / 10000);
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
      realLoading={content === null}
      onScrollRatio={handleScrollRatio}
      initialScrollRatio={initialScrollRatio}
    />
  );
}
