import type { ReceiptView } from './envelope';
import { request } from './http';

/**
 * 作品域服务（对齐后端 catalog/authoring 切片：GET /api/v1/me/works、
 * POST /api/v1/works、POST /api/v1/drafts、POST submissions；05 §3.2）。
 */
export interface MyWork {
  id: string;
  state: string;
  title: string | null;
  draftId: string | null;
  draftState: string | null;
  publishedVersionId: string | null;
  revision: number;
}

export interface DraftPayload {
  workId: string;
  chapterId?: string;
  title: string;
  synopsis?: string;
  categoryId?: string;
  tags?: string[];
  body?: string;
}

export async function listMyWorks(): Promise<MyWork[]> {
  const envelope = await request<{ items: MyWork[]; nextCursor: string | null }>({
    method: 'GET',
    path: '/me/works',
  });
  return envelope.data.items;
}

export async function createWork(): Promise<ReceiptView> {
  const envelope = await request<ReceiptView>({
    method: 'POST',
    path: '/works',
    idempotencyKey: crypto.randomUUID(),
    body: {},
  });
  return envelope.data;
}

export async function createDraft(payload: DraftPayload): Promise<ReceiptView> {
  const envelope = await request<ReceiptView>({
    method: 'POST',
    path: '/drafts',
    idempotencyKey: crypto.randomUUID(),
    body: payload,
  });
  return envelope.data;
}

export async function saveDraft(
  draftId: string,
  ifMatch: string,
  payload: DraftPayload,
): Promise<ReceiptView> {
  const envelope = await request<ReceiptView>({
    method: 'PUT',
    path: `/drafts/${draftId}`,
    ifMatch,
    idempotencyKey: crypto.randomUUID(),
    body: payload,
  });
  return envelope.data;
}

export async function submitDraft(
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

export const workService = { listMyWorks, createWork };
export const draftService = { createDraft, saveDraft, submitDraft };
