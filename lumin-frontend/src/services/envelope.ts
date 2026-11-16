/**
 * 契约响应信封类型（05 §2 / §4.4 与 contracts/p0.openapi.yaml SuccessBase 对齐）：
 * code/message/data/traceId 全部必填；成功 code=OK；错误 data=null。
 * 字段口径以后续从契约生成的三端类型为准，此处为过渡手写声明（登记：生成器接入后替换）。
 */
export interface ApiEnvelope<T> {
  code: string;
  message: string;
  data: T;
  traceId: string;
}

/** 同步变更回执（05 §4.4 Receipt）。 */
export interface ReceiptView {
  id: string;
  resourceType: string;
  state: string;
  revision: number;
}

/** 服务端业务码（libs/error-codes.json 全集），错误信封 data=null。 */
export type ApiErrorCode =
  | 'OK'
  | 'P1001'
  | 'P1002'
  | 'A0001'
  | 'A2001'
  | 'A2002'
  | 'A2003'
  | 'B2001'
  | 'B2002'
  | 'B2003'
  | 'B2004'
  | 'B2005'
  | 'D2001'
  | 'D2002';
