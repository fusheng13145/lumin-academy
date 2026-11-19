import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CatalogView } from './CatalogView';
import type { WorkListItemDto, FeaturedItemDto } from '../../services/readerService';

const works: WorkListItemDto[] = [
  { versionId: 'v-1', workId: 'id-1', title: '自然作品', author: '佚名', grade: 'G', chapterId: 'c-1' },
];
const featured: FeaturedItemDto[] = [
  { workId: 'id-2', versionId: 'v-2', title: '精选之作', author: '阿澜', grade: 'G',
    chapterId: 'c-2', exposureReason: 'FEATURED', intervention: true, positionRank: 10 },
];

const envelope = (data: unknown) => new Response(JSON.stringify({
  code: 'OK', message: '', data, traceId: 'featured-test',
}), { status: 200, headers: { 'Content-Type': 'application/json' } });

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockImplementation(async (input) => {
    const url = String(input);
    if (url === '/api/v1/works') return envelope(works);
    if (url === '/api/v1/works/featured') return envelope(featured);
    throw new Error(`Unexpected request: ${url}`);
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('CatalogView 运营精选位（UGC-039）', () => {
  it('精选位独立于自然列表，干预标识显式', async () => {
    render(<CatalogView source="live" ageBand="C3" announce={() => {}}
      onOpenDetail={() => {}} onSourceChange={() => {}} />);
    expect(await screen.findByText('精选之作')).toBeTruthy();
    expect(screen.getByText('运营推荐 · 推荐位')).toBeTruthy();
    expect(screen.getByText('自然作品')).toBeTruthy();
    expect(screen.getByRole('region', { name: '运营推荐' })).toBeTruthy();
  });

  it('精选为空时不渲染推荐区块', async () => {
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/v1/works') return envelope(works);
      if (url === '/api/v1/works/featured') return envelope([]);
      throw new Error(`Unexpected request: ${url}`);
    });
    render(<CatalogView source="live" ageBand="C3" announce={() => {}}
      onOpenDetail={() => {}} onSourceChange={() => {}} />);
    expect(await screen.findByText('自然作品')).toBeTruthy();
    expect(screen.queryByText('运营推荐 · 推荐位')).toBeNull();
  });
});
