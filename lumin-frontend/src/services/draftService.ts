import type { ReceiptView } from './envelope';
import { request } from './http';

/**
 * 草稿域服务（对齐后端 authoring 切片；05 §3.2）。
 */
export interface DraftPayload {
  workId: string;
  chapterId?: string;
  title: string;
  synopsis?: string;
  categoryId?: string;
  tags?: string[];
  body?: string;
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

export async function getDraft(draftId: string): Promise<unknown> {
  const envelope = await request<unknown>({ method: 'GET', path: `/drafts/${draftId}` });
  return envelope.data;
}

export const draftService = { createDraft, saveDraft, submitDraft, getDraft };
