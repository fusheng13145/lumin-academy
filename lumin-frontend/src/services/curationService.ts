import { request } from './http';

/**
 * 运营精选域服务（docs/13 UGC-039 二阶段；04 §6.1 curation_item）。
 * 提案走 OPERATOR 替身、审批/撤位走 ADMIN 替身——职责分离在请求头体现，
 * 与后端服务层角色闸双口径一致。
 */
const OPERATOR = { 'X-Lumin-Demo-Subject': 'OPERATOR' };
const ADMIN = { 'X-Lumin-Demo-Subject': 'ADMIN' };

/** 曝光原因字典标签（干预显式化；与 ck_curation_reason 同源）。 */
export const EXPOSURE_LABEL: Record<string, string> = {
  FEATURED: '推荐位',
  EDITOR_PICK: '编辑推荐',
  CATEGORY_SPOTLIGHT: '分类聚焦',
};

export const EXPOSURE_REASONS = Object.keys(EXPOSURE_LABEL);

export interface CurationItemDto {
  id: string;
  ruleVersion: number;
  workId: string;
  exposureReason: string;
  note: string;
  state: string;
  positionRank: number;
  proposedBy: string;
  reviewedBy: string;
  createdAt: string;
}

export async function listCurationItems(state?: string): Promise<CurationItemDto[]> {
  const query = state ? `?state=${encodeURIComponent(state)}` : '';
  const envelope = await request<CurationItemDto[]>({
    method: 'GET',
    path: `/curation-items${query}`,
    headers: OPERATOR,
  });
  return envelope.data;
}

export async function proposeCurationItem(
    workId: string,
    exposureReason: string,
    note: string,
): Promise<void> {
  await request({
    method: 'POST',
    path: '/curation-items',
    headers: OPERATOR,
    idempotencyKey: crypto.randomUUID(),
    body: { workId, exposureReason, note: note === '' ? null : note },
  });
}

export async function reviewCurationItem(id: string, decision: 'APPROVE' | 'REJECT'): Promise<void> {
  await request({
    method: 'POST',
    path: `/curation-items/${id}/reviews`,
    headers: ADMIN,
    idempotencyKey: crypto.randomUUID(),
    body: { decision },
  });
}

export async function retireCurationItem(id: string): Promise<void> {
  await request({
    method: 'DELETE',
    path: `/curation-items/${id}`,
    headers: ADMIN,
    idempotencyKey: crypto.randomUUID(),
    body: {},
  });
}

export const curationService = {
  listCurationItems,
  proposeCurationItem,
  reviewCurationItem,
  retireCurationItem,
};
