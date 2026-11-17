import { request } from './http';

/**
 * 章节域服务（对齐后端 catalog/authoring 切片；05 §3.2）。
 */
export interface MyChapter {
  id: string;
  sequenceNo: number;
  businessState: string;
  draftId: string | null;
  draftState: string | null;
  draftTitle: string | null;
}

export async function listMyChapters(workId: string): Promise<MyChapter[]> {
  const envelope = await request<MyChapter[]>({
    method: 'GET',
    path: `/me/works/${workId}/chapters`,
  });
  return envelope.data;
}

export async function createChapter(workId: string): Promise<{ id: string }> {
  const envelope = await request<{ id: string }>({
    method: 'POST',
    path: `/works/${workId}/chapters`,
    idempotencyKey: crypto.randomUUID(),
    ifMatch: '1',
    body: {},
  });
  return envelope.data;
}

export async function createChapterDraft(
  workId: string,
  chapterId: string,
  title: string,
  body: string,
): Promise<{ id: string; revision: number }> {
  const envelope = await request<{ id: string; revision: number }>({
    method: 'POST',
    path: '/drafts',
    idempotencyKey: crypto.randomUUID(),
    body: { workId, chapterId, title, body },
  });
  return envelope.data;
}

export async function submitChapterDraft(
  draftId: string,
  ifMatch: string,
): Promise<{ workflowId: string; versionId: string; state: string }> {
  const envelope = await request<{
    workflowId: string;
    versionId: string;
    state: string;
  }>({
    method: 'POST',
    path: `/drafts/${draftId}/submissions`,
    ifMatch,
    idempotencyKey: crypto.randomUUID(),
    body: {},
  });
  return envelope.data;
}

export const chapterService = { listMyChapters, createChapter, createChapterDraft, submitChapterDraft };
