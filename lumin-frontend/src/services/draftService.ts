import type { ReceiptView } from './envelope';
// 联调批次接入真实接口时恢复：import { request } from './http';

/**
 * 草稿域服务（对齐后端 authoring 垂直切片：POST /api/v1/drafts、
 * GET/PUT /api/v1/drafts/{id}；05 §3.2）。
 *
 * **当前为 mock adapter（登记）**：页面渲染仍使用 src/mocks 夹具；
 * 联调批次将下列实现切换为 request() 调用，视图层签名不变。
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

export async function createWork(): Promise<ReceiptView> {
  // 联调批次切换为：request<ReceiptView>({ method: 'POST', path: '/works', idempotencyKey, body: {} }).then(e => e.data)
  throw new Error('createWork：接口联调批次接入（当前演示不发网络请求）');
}

export async function createDraft(_payload: DraftPayload, _idempotencyKey: string): Promise<ReceiptView> {
  void _payload;
  void _idempotencyKey;
  throw new Error('createDraft：接口联调批次接入（当前演示不发网络请求）');
}

export async function saveDraft(
  _draftId: string,
  _ifMatch: string,
  _payload: DraftPayload,
  _idempotencyKey: string,
): Promise<ReceiptView> {
  void _draftId;
  void _ifMatch;
  void _payload;
  void _idempotencyKey;
  throw new Error('saveDraft：接口联调批次接入（当前演示不发网络请求）');
}

export async function getDraft(_draftId: string): Promise<unknown> {
  void _draftId;
  throw new Error('getDraft：接口联调批次接入（当前演示不发网络请求）');
}

export const draftService = { createWork, createDraft, saveDraft, getDraft };
