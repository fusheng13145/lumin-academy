import { render, screen, cleanup, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OperatorView } from './OperatorView';

const items = [
  { id: 'cur-1', ruleVersion: 1, workId: 'work-aaaa-1', exposureReason: 'FEATURED',
    note: '', state: 'PENDING', positionRank: 0, proposedBy: 'op-1111', reviewedBy: '',
    createdAt: '2026-11-20T10:00:00Z' },
  { id: 'cur-2', ruleVersion: 1, workId: 'work-bbbb-2', exposureReason: 'EDITOR_PICK',
    note: '重点位', state: 'ACTIVE', positionRank: 10, proposedBy: 'op-1111',
    reviewedBy: 'ad-2222', createdAt: '2026-11-20T09:00:00Z' },
];

const envelope = (data: unknown) => new Response(JSON.stringify({
  code: 'OK', message: '', data, traceId: 'curation-ui-test',
}), { status: 200, headers: { 'Content-Type': 'application/json' } });

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  window.location.hash = '';
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    if (method === 'GET' && url === '/api/v1/config-revisions') return envelope([]);
    if (method === 'GET' && url.startsWith('/api/v1/metrics/')) {
      const shape = url.includes('retention')
        ? { frozenDefinitionVersion: 1, cohorts: [] }
        : url.includes('supply')
          ? { frozenDefinitionVersion: 1, submissions: 0, approved: 0, rejected: 0,
              takedownActive: 0, featuredActive: 0, realReaders: 0, syntheticProfiles: 0 }
          : { frozenDefinitionVersion: 1, windowDays: 7, windowStart: '2026-11-13T00:00:00Z',
              effectiveReads: 0, syntheticExcluded: 0, byBand: [] };
      return envelope(shape);
    }
    if (method === 'GET' && url === '/api/v1/curation-items') return envelope(items);
    if (method === 'POST' && url === '/api/v1/curation-items') return envelope({ id: 'new' });
    if (method === 'POST' && url === '/api/v1/curation-items/cur-1/reviews') {
      return envelope({ id: 'cur-1', state: 'ACTIVE' });
    }
    if (method === 'DELETE' && url === '/api/v1/curation-items/cur-2') {
      return envelope({ id: 'cur-2', state: 'RETIRED' });
    }
    throw new Error(`Unexpected request: ${method} ${url}`);
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('OperatorView 运营精选工作台（UGC-039 二阶段）', () => {
  it('提案表单：输入作品与曝光原因 → OPERATOR 身份提交', async () => {
    const user = userEvent.setup();
    render(<OperatorView />);
    await screen.findByText('运营精选（曝光干预 · 显式标注）');
    await user.type(screen.getByLabelText('作品 ID'), 'work-new-9');
    await user.selectOptions(screen.getByLabelText('曝光原因'), 'EDITOR_PICK');
    await user.type(screen.getByLabelText('运营备注'), '首页实验');
    await user.click(screen.getByRole('button', { name: '提交精选提案' }));
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/curation-items',
        expect.objectContaining({ method: 'POST' }),
      );
    });
    const call = fetchMock.mock.calls.find(
      ([url, init]) => String(url) === '/api/v1/curation-items' && init?.method === 'POST',
    );
    const headers = new Headers((call?.[1] as RequestInit).headers);
    expect(headers.get('X-Lumin-Demo-Subject')).toBe('OPERATOR');
    expect(headers.get('Idempotency-Key')).toBeTruthy();
  });

  it('列表按态渲染操作：PENDING 审批走 ADMIN、ACTIVE 可撤位', async () => {
    const user = userEvent.setup();
    render(<OperatorView />);
    expect(await screen.findByText('运营精选（曝光干预 · 显式标注）')).toBeTruthy();
    // PENDING 行：审批生效 + 驳回
    expect(screen.getByText('work-aaa… · 推荐位 · PENDING')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '审批生效' }));
    await waitFor(() => {
      const call = fetchMock.mock.calls.find(
        ([url]) => String(url) === '/api/v1/curation-items/cur-1/reviews',
      );
      expect(call).toBeTruthy();
      const headers = new Headers((call?.[1] as RequestInit).headers);
      expect(headers.get('X-Lumin-Demo-Subject')).toBe('ADMIN');
    });
    // ACTIVE 行：撤位
    expect(screen.getByText('work-bbb… · 编辑推荐 · ACTIVE')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: '撤位' }));
    await waitFor(() => {
      const call = fetchMock.mock.calls.find(
        ([url, init]) =>
          String(url) === '/api/v1/curation-items/cur-2' && init?.method === 'DELETE',
      );
      expect(call).toBeTruthy();
      const headers = new Headers((call?.[1] as RequestInit).headers);
      expect(headers.get('X-Lumin-Demo-Subject')).toBe('ADMIN');
    });
  });

  it('空列表如实呈现「暂无精选提案」', async () => {
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      if (method === 'GET' && url === '/api/v1/config-revisions') return envelope([]);
      if (method === 'GET' && url.startsWith('/api/v1/metrics/')) {
        const shape = url.includes('retention')
          ? { frozenDefinitionVersion: 1, cohorts: [] }
          : url.includes('supply')
            ? { frozenDefinitionVersion: 1, submissions: 0, approved: 0, rejected: 0,
                takedownActive: 0, featuredActive: 0, realReaders: 0, syntheticProfiles: 0 }
            : { frozenDefinitionVersion: 1, windowDays: 7,
                windowStart: '2026-11-13T00:00:00Z', effectiveReads: 0,
                syntheticExcluded: 0, byBand: [] };
        return envelope(shape);
      }
      if (method === 'GET' && url === '/api/v1/curation-items') return envelope([]);
      throw new Error(`Unexpected request: ${method} ${url}`);
    });
    render(<OperatorView />);
    expect(await screen.findByText('暂无精选提案')).toBeTruthy();
  });
});
