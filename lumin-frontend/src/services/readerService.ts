import { request, ApiError } from './http';
import type { AgeBand } from '../types';

/**
 * 读者域服务（05 §3.4：listWorks / getWork / listChapters / readChapter）。
 * 未授权统一 404（B2004），正文由服务端可见性闸门返回。
 *
 * 读者年龄带经有界替身头（READER_C0–C4）显式随请求送达后端——
 * 服务端按 effective_age_band 真实裁决；仅读者链请求携带，不影响创作/审核替身。
 */
function readerHeaders(band?: AgeBand | null): Record<string, string> {
  return band ? { 'X-Lumin-Demo-Subject': `READER_${band}` } : {};
}
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

export async function listWorks(band?: AgeBand | null): Promise<WorkListItemDto[]> {
  const envelope = await request<WorkListItemDto[]>({
    method: 'GET',
    path: '/works',
    headers: readerHeaders(band),
  });
  return envelope.data;
}

export async function getWork(workId: string, band?: AgeBand | null): Promise<WorkDetailDto> {
  const envelope = await request<WorkDetailDto>({
    method: 'GET',
    path: `/works/${workId}`,
    headers: readerHeaders(band),
  });
  return envelope.data;
}

export async function listChapters(
  workId: string,
  band?: AgeBand | null,
): Promise<ChapterEntryDto[]> {
  const envelope = await request<ChapterEntryDto[]>({
    method: 'GET',
    path: `/works/${workId}/chapters`,
    headers: readerHeaders(band),
  });
  return envelope.data;
}

export async function readChapter(
  chapterId: string,
  band?: AgeBand | null,
): Promise<ContentDto> {
  const envelope = await request<ContentDto>({
    method: 'GET',
    path: `/chapters/${chapterId}/content`,
    headers: readerHeaders(band),
  });
  return envelope.data;
}

/** 本人阅读进度（05 §3.4 getProgress；不存在返回 null——未读过/越权同为 404 语义）。 */
export async function getProgress(
  chapterId: string,
  band?: AgeBand | null,
): Promise<ProgressDto | null> {
  try {
    const envelope = await request<ProgressDto>({
      method: 'GET',
      path: `/me/reading-progress/${chapterId}`,
      headers: readerHeaders(band),
    });
    return envelope.data;
  } catch (e) {
    if (e instanceof ApiError && e.httpStatus === 404) {
      return null;
    }
    throw e;
  }
}

/** 进度视图（契约：位置、版本与 ETag）。 */
export interface ProgressDto {
  chapterId: string;
  versionId: string;
  positionBasisPoints: number;
  readSeconds: number;
  clientUpdatedAt: string;
  etag: string;
}

export const readerService = { listWorks, getWork, listChapters, readChapter, getProgress };
