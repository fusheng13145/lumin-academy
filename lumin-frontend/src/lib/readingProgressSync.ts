/**
 * 进度同步决策纯函数（05 §3.4；docs/13 UGC-016）：何时保存、readSeconds
 * 累计口径、跨版本恢复判定。无 IO、无时钟读取；组件只做接线。
 */

export type SyncDecision = 'skip' | 'save';

/**
 * 位置变化 ≥ 100bp（1%）才触发保存（避免逐行滚动打请求）；
 * force=true 用于章节离开/阅读器关闭的兜底落盘。
 * lastSavedBp=null 表示本章尚未保存过（位置 > 0 才值得写）。
 */
export function shouldSave(
    lastSavedBp: number | null,
    currentBp: number,
    force: boolean,
): SyncDecision {
  if (force) {
    return 'save';
  }
  if (lastSavedBp === null) {
    return currentBp > 0 ? 'save' : 'skip';
  }
  return Math.abs(currentBp - lastSavedBp) >= 100 ? 'save' : 'skip';
}

/**
 * readSeconds 累计：服务端按整值替换保存，前端发「历史累计 + 本次会话增量」，
 * 不发会话增量本身（否则每次保存都会清零历史）。
 */
export function cumulativeReadSeconds(prevTotal: number, sessionDelta: number): number {
  return Math.max(0, Math.floor(prevTotal)) + Math.max(0, Math.floor(sessionDelta));
}

/**
 * 恢复判定：仅同版本可恢复位置——版本不同不跨版本套用
 * （04 §5.1「版本变化提示而不跨版本乱套位置」）。
 */
export function canRestore(
    progressVersionId: string | null | undefined,
    contentVersionId: string | null | undefined,
): boolean {
  return progressVersionId != null && contentVersionId != null
      && progressVersionId === contentVersionId;
}

/** 滚动比例 → 万分比（0–10000，边界钳制；容器不可滚动时为 0）。 */
export function scrollRatioToBasisPoints(ratio: number): number {
  if (!Number.isFinite(ratio) || ratio <= 0) {
    return 0;
  }
  return Math.min(10000, Math.round(ratio * 10000));
}
