import { request } from './http';

/**
 * 书架域服务（docs/13 UGC-035；05 §3.4）：收藏/取消收藏/书架列表/阅读历史。
 * 可读状态口径由后端给出（下架/无公开指针 → readable=false，前端灰显）。
 */
export interface BookshelfItem {
  workId: string;
  title: string;
  author: string;
  grade: string;
  workState: string;
  readable: boolean;
  bookmarkedAt: string;
}

export interface ReadingHistoryItem {
  chapterId: string;
  workId: string;
  workTitle: string;
  chapterTitle: string;
  sequenceNo: number;
  positionBasisPoints: number;
  readable: boolean;
  updatedAt: string;
}

export async function listBookmarks(): Promise<BookshelfItem[]> {
  const envelope = await request<BookshelfItem[]>({
    method: 'GET',
    path: '/me/bookmarks',
  });
  return envelope.data;
}

export async function removeBookmark(workId: string): Promise<void> {
  await request({
    method: 'DELETE',
    path: `/me/bookmarks/${workId}`,
    idempotencyKey: crypto.randomUUID(),
    body: {},
  });
}

export async function addBookmark(workId: string): Promise<void> {
  await request({
    method: 'POST',
    path: '/me/bookmarks',
    idempotencyKey: crypto.randomUUID(),
    body: { workId },
  });
}

export async function listReadingHistory(): Promise<ReadingHistoryItem[]> {
  const envelope = await request<ReadingHistoryItem[]>({
    method: 'GET',
    path: '/me/reading-history',
  });
  return envelope.data;
}

export const bookshelfService = {
  listBookmarks,
  removeBookmark,
  addBookmark,
  listReadingHistory,
};
