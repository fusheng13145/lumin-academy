import { request } from './http';

/**
 * 通知域服务（05 §3.4：listNotifications / markNotificationRead）。
 * 仅本人可见；重复已读幂等（T33）。
 */
export interface NotificationItem {
  id: string;
  category: string;
  title: string;
  body: string;
  resourceType: string;
  resourceId: string;
}

export async function listNotifications(): Promise<NotificationItem[]> {
  const envelope = await request<NotificationItem[]>({
    method: 'GET',
    path: '/me/notifications',
  });
  return envelope.data;
}

export async function markNotificationRead(id: string): Promise<void> {
  await request<{ id: string; read: boolean }>({
    method: 'POST',
    path: `/me/notifications/${id}/read-receipts`,
    idempotencyKey: crypto.randomUUID(),
    body: {},
  });
}

export const notificationService = { listNotifications, markNotificationRead };
