import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OperatorView } from './OperatorView';

const envelope = (data: unknown) => new Response(JSON.stringify({
  code: 'OK', message: '', data, traceId: 'metrics-test',
}), { status: 200, headers: { 'Content-Type': 'application/json' } });

const reading = {
  frozenDefinitionVersion: 1, windowDays: 7, windowStart: '2026-11-13T00:00:00Z',
  effectiveReads: 12, syntheticExcluded: 3,
  byBand: [{ band: 'C2', reads: 7 }, { band: 'C3', reads: 5 }],
};
const retention = {
  frozenDefinitionVersion: 1,
  cohorts: [{ weekStart: '2026-11-09', cohortSize: 3, retainedCount: 1, retentionRate: 0.3333 }],
};
const supply = {
  frozenDefinitionVersion: 1, submissions: 8, approved: 6, rejected: 1,
  takedownActive: 1, featuredActive: 2, realReaders: 4, syntheticProfiles: 9,
};

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockImplementation(async (input) => {
    const url = String(input);
    if (url === '/api/v1/config-revisions') return envelope([]);
    if (url === '/api/v1/metrics/reading?days=7') return envelope(reading);
    if (url === '/api/v1/metrics/retention') return envelope(retention);
    if (url === '/api/v1/metrics/supply') return envelope(supply);
    throw new Error(`Unexpected request: ${url}`);
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('OperatorView 可信看板（UGC-040）', () => {
  it('读数带口径版本与冻结读数，合成流量剔除量透明', async () => {
    render(<OperatorView />);
    const board = await screen.findByLabelText('供给与阅读看板');
    expect(board.textContent).toContain('供给与阅读看板（口径 v1）');
    expect(board.textContent).toContain('有效阅读（近 7 天，去重读者×章节）：12');
    expect(board.textContent).toContain('已剔除合成流量 3');
    expect(board.textContent).toContain('C2 7 · C3 5');
    expect(board.textContent).toContain('2026-11-09 群 3 留存 1（33%）');
    expect(board.textContent).toContain('提交 8');
  });

  it('无数据如实呈现「暂无数据（无数据不编数字）」，分群空数组不造 0%', async () => {
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/v1/config-revisions') return envelope([]);
      if (url === '/api/v1/metrics/reading?days=7') {
        return envelope({ ...reading, effectiveReads: 0, syntheticExcluded: 0, byBand: [] });
      }
      if (url === '/api/v1/metrics/retention') return envelope({ frozenDefinitionVersion: 1, cohorts: [] });
      if (url === '/api/v1/metrics/supply') return envelope(supply);
      throw new Error(`Unexpected request: ${url}`);
    });
    render(<OperatorView />);
    expect(await screen.findByText('分群：暂无数据（无数据不编数字）')).toBeTruthy();
    expect(screen.getByText('次周留存：暂无分群数据（无数据不编数字）')).toBeTruthy();
    expect(screen.queryByText(/留存 0/)).toBeNull();
  });

  it('看板不可用如实报错（与空数据分开）', async () => {
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/v1/config-revisions') return envelope([]);
      if (url === '/api/v1/curation-items') return envelope([]);
      if (url.startsWith('/api/v1/metrics')) {
        return new Response(JSON.stringify({
          code: 'D2001', message: '依赖不可用', data: null, traceId: 'm',
        }), { status: 503, headers: { 'Content-Type': 'application/json' } });
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    render(<OperatorView />);
    expect((await screen.findByRole('alert')).textContent).toContain('看板加载失败');
  });
});
