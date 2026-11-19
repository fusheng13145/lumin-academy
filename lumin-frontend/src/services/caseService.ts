import { request } from './http';

/**
 * 审核案件服务（对齐后端 moderation 切片：GET/POST /api/v1/review-cases；05 §3.3）。
 * 替身身份经 X-Lumin-Demo-Subject 头（骨架口径）。
 */
export interface DecisionView {
  id: string;
  actorId: string;
  decision: string;
  grade: string | null;
  reasonCode: string;
  reason: string;
  evidenceRefs: string[];
  decidedAt: string;
}

export interface ReviewCase {
  id: string;
  workId: string;
  versionId?: string | null;
  state: string;
  risk: string;
  priority: string;
  authorAgeBand: string;
  completionDueAt: string;
  leaseExpiresAt?: string | null;
  revision: number;
  decisions: DecisionView[];
  stage?: number;
  assigneeId?: string | null;
}

const DEMO_HEADERS: Record<string, string> = {
  'X-Lumin-Demo-Subject': 'REVIEWER_L1A',
};

export async function listCases(): Promise<ReviewCase[]> {
  const envelope = await request<{ items: ReviewCase[]; nextCursor: string | null }>({
    method: 'GET',
    path: '/review-cases',
    headers: DEMO_HEADERS,
  });
  return envelope.data.items;
}

export async function claimCase(caseId: string, ifMatch: string): Promise<ReviewCase> {
  const envelope = await request<ReviewCase>({
    method: 'POST',
    path: `/review-cases/${caseId}/claims`,
    ifMatch,
    idempotencyKey: crypto.randomUUID(),
    headers: DEMO_HEADERS,
    body: {},
  });
  return envelope.data;
}

export async function decideCase(
  caseId: string,
  ifMatch: string,
  body: { decision: string; grade?: string; reasonCode: string; reason: string },
): Promise<ReviewCase> {
  const envelope = await request<ReviewCase>({
    method: 'POST',
    path: `/review-cases/${caseId}/decisions`,
    ifMatch,
    idempotencyKey: crypto.randomUUID(),
    headers: DEMO_HEADERS,
    body,
  });
  return envelope.data;
}

export const caseService = { listCases, claimCase, decideCase };
