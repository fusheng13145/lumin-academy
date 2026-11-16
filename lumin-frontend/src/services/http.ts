import type { ApiEnvelope } from './envelope';

/**
 * HTTP 客户端骨架（05 §2 全局协议）：Bearer 身份、写操作 Idempotency-Key、
 * 可变资源 If-Match、统一信封解析。
 *
 * **当前未接线（登记）**：P0-A/演示阶段页面全部走 src/mocks 合成夹具、不发网络请求
 * （前端 README「与后端的边界」）；后端切片端点（/api/v1/works、/api/v1/drafts）联调
 * 批次接入时启用本文件，并以真实 fetch 替换各 service 的 mock adapter。
 */
export class ApiError extends Error {
  readonly code: string;
  readonly traceId: string;
  readonly httpStatus: number;

  constructor(code: string, message: string, traceId: string, httpStatus: number) {
    super(`${code}: ${message}`);
    this.name = 'ApiError';
    this.code = code;
    this.traceId = traceId;
    this.httpStatus = httpStatus;
  }
}

const BASE_PATH = '/api/v1';

export interface RequestOptions {
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  path: string;
  /** 写操作必须提供 UUID（05 §2 写幂等）。 */
  idempotencyKey?: string;
  /** 可变资源乐观锁版本（带引号正整数，05 §2）。 */
  ifMatch?: string;
  body?: unknown;
  signal?: AbortSignal;
}

export async function request<T>(options: RequestOptions): Promise<ApiEnvelope<T>> {
  const headers = new Headers({ 'Content-Type': 'application/json' });
  if (options.idempotencyKey) {
    headers.set('Idempotency-Key', options.idempotencyKey);
  }
  if (options.ifMatch) {
    headers.set('If-Match', options.ifMatch);
  }
  const response = await fetch(`${BASE_PATH}${options.path}`, {
    method: options.method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  });
  const payload = (await response.json()) as ApiEnvelope<T>;
  if (!response.ok || payload.code !== 'OK') {
    throw new ApiError(payload.code, payload.message, payload.traceId, response.status);
  }
  return payload;
}
