import { request } from './http';

/**
 * 读者域服务（05 §3.4：listWorks / getWork / listChapters / readChapter）。
 * 未授权统一 404（B2004），正文由服务端可见性闸门返回。
 */
export interface WorkListItemDto {
  versionId: string;
  workId: string;
  title: string;
  author: string;
  grade: string;
  chapterId: string;
}

export interface WorkDetailDto {
  versionId: string;
  workId: string;
  title: string;
  author: string;
  synopsis: string | null;
  categoryId: string | null;
  tags: string[];
  grade: string;
}

export interface ChapterEntryDto {
  id: string;
  sequenceNo: number;
  businessState: string;
}

export interface ContentDto {
  chapterId: string;
  versionId: string;
  title: string;
  body: string;
  grade: string;
}

export async function listWorks(): Promise<WorkListItemDto[]> {
  const envelope = await request<WorkListItemDto[]>({ method: 'GET', path: '/works' });
  return envelope.data;
}

export async function getWork(workId: string): Promise<WorkDetailDto> {
  const envelope = await request<WorkDetailDto>({ method: 'GET', path: `/works/${workId}` });
  return envelope.data;
}

export async function listChapters(workId: string): Promise<ChapterEntryDto[]> {
  const envelope = await request<ChapterEntryDto[]>({
    method: 'GET',
    path: `/works/${workId}/chapters`,
  });
  return envelope.data;
}

export async function readChapter(chapterId: string): Promise<ContentDto> {
  const envelope = await request<ContentDto>({
    method: 'GET',
    path: `/chapters/${chapterId}/content`,
  });
  return envelope.data;
}

export const readerService = { listWorks, getWork, listChapters, readChapter };
