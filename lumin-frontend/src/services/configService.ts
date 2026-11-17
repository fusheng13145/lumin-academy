import { request } from './http';

/**
 * 配置域服务（05 §3.4 config-revisions：提案/审批/激活/审查列表）。
 * 提案走 OPERATOR 替身、审批与激活走 ADMIN 替身——职责分离在请求头体现，
 * 与后端服务层校验（自批拒绝）双口径一致。
 */
export interface ConfigRevisionDto {
  id: string;
  configKey: string;
  revision: number;
  baseRevision: number;
  state: string;
  payload: string;
  reason: string;
  requestedBy: string;
  approvedBy: string | null;
  effectiveAt: string;
  rolloutBasisPoints: number;
  rollbackOf: string | null;
  lockVersion: number;
}

const OPERATOR = { 'X-Lumin-Demo-Subject': 'OPERATOR' };
const ADMIN = { 'X-Lumin-Demo-Subject': 'ADMIN' };

export async function listConfigRevisions(configKey?: string): Promise<ConfigRevisionDto[]> {
  const query = configKey ? `?configKey=${encodeURIComponent(configKey)}` : '';
  const envelope = await request<ConfigRevisionDto[]>({
    method: 'GET',
    path: `/config-revisions${query}`,
    headers: ADMIN,
  });
  return envelope.data;
}

export async function proposeConfigRevision(
  configKey: string,
  payload: string,
  reason: string,
  rollbackOf?: string,
): Promise<{ id: string; state: string }> {
  const envelope = await request<{ id: string; state: string }>({
    method: 'POST',
    path: '/config-revisions',
    idempotencyKey: crypto.randomUUID(),
    headers: OPERATOR,
    body: {
      configKey,
      payload,
      reason,
      ...(rollbackOf ? { rollbackOf } : {}),
    },
  });
  return envelope.data;
}

export async function reviewConfigRevision(
  id: string,
  lockVersion: number,
  decision: 'APPROVE' | 'REJECT',
  legalConfirmation: boolean,
): Promise<{ id: string; state: string }> {
  const envelope = await request<{ id: string; state: string }>({
    method: 'POST',
    path: `/config-revisions/${id}/reviews`,
    idempotencyKey: crypto.randomUUID(),
    ifMatch: String(lockVersion),
    headers: ADMIN,
    body: { decision, reason: decision === 'APPROVE' ? '合规确认' : '驳回', legalConfirmation },
  });
  return envelope.data;
}

export async function activateConfigRevision(
  id: string,
): Promise<{ id: string; state: string }> {
  const envelope = await request<{ id: string; state: string }>({
    method: 'POST',
    path: `/config-revisions/${id}/activations`,
    idempotencyKey: crypto.randomUUID(),
    headers: ADMIN,
    body: {},
  });
  return envelope.data;
}

export const configService = {
  listConfigRevisions,
  proposeConfigRevision,
  reviewConfigRevision,
  activateConfigRevision,
};
