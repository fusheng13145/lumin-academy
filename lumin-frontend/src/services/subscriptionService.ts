import { request } from './http';

/**
 * 追更订阅域服务（docs/13 UGC-036；04 §6.1 subscription）：
 * 订阅/退订/订阅列表。收藏≠订阅（书架不产生订阅行）；
 * 未成年推送开关由后端 fail-closed 收窄，前端以响应生效值展示。
 */
export interface SubscriptionItem {
  workId: string;
  title: string;
  author: string;
  workState: string;
  subscribedAt: string;
  quietStart: string;
  quietEnd: string;
  pushEnabled: boolean;
  timezoneId: string;
}

export async function listSubscriptions(): Promise<SubscriptionItem[]> {
  const envelope = await request<SubscriptionItem[]>({
    method: 'GET',
    path: '/me/subscriptions',
  });
  return envelope.data;
}

export async function subscribe(workId: string): Promise<void> {
  await request({
    method: 'POST',
    path: '/me/subscriptions',
    idempotencyKey: crypto.randomUUID(),
    body: { workId },
  });
}

export async function unsubscribe(workId: string): Promise<void> {
  await request({
    method: 'DELETE',
    path: `/me/subscriptions/${workId}`,
    idempotencyKey: crypto.randomUUID(),
    body: {},
  });
}

export const subscriptionService = {
  listSubscriptions,
  subscribe,
  unsubscribe,
};
