import { request } from './http';

/**
 * 可信看板域服务（docs/13 UGC-040 一阶段）：OPERATOR/ADMIN 只读。
 * 每个读数带 frozenDefinitionVersion（口径冻结版本化）；空数据由后端如实
 * 返回空集——前端不做任何编造。
 */
const OPERATOR = { 'X-Lumin-Demo-Subject': 'OPERATOR' };

export interface ReadingMetricsDto {
  frozenDefinitionVersion: number;
  windowDays: number;
  windowStart: string;
  effectiveReads: number;
  syntheticExcluded: number;
  byBand: { band: string; reads: number }[];
}

export interface RetentionCohortDto {
  weekStart: string;
  cohortSize: number;
  retainedCount: number;
  retentionRate: number;
}

export interface RetentionMetricsDto {
  frozenDefinitionVersion: number;
  cohorts: RetentionCohortDto[];
}

export interface SupplyMetricsDto {
  frozenDefinitionVersion: number;
  submissions: number;
  approved: number;
  rejected: number;
  takedownActive: number;
  featuredActive: number;
  realReaders: number;
  syntheticProfiles: number;
}

export async function listReadingMetrics(days = 7): Promise<ReadingMetricsDto> {
  const envelope = await request<ReadingMetricsDto>({
    method: 'GET',
    path: `/metrics/reading?days=${days}`,
    headers: OPERATOR,
  });
  return envelope.data;
}

export async function listRetentionMetrics(): Promise<RetentionMetricsDto> {
  const envelope = await request<RetentionMetricsDto>({
    method: 'GET',
    path: '/metrics/retention',
    headers: OPERATOR,
  });
  return envelope.data;
}

export async function listSupplyMetrics(): Promise<SupplyMetricsDto> {
  const envelope = await request<SupplyMetricsDto>({
    method: 'GET',
    path: '/metrics/supply',
    headers: OPERATOR,
  });
  return envelope.data;
}

export const metricsService = { listReadingMetrics, listRetentionMetrics, listSupplyMetrics };
