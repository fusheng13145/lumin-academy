import type { IconName } from '../components/icons/Icon';
import type { WorkspaceId } from '../types';

/** 工作空间导航的纯配置；Web 壳与共享布局共用，不依赖 App。 */
export const WORKSPACES: { id: WorkspaceId; label: string; icon: IconName }[] = [
  { id: 'catalog', label: '书城', icon: 'catalog' },
  { id: 'author', label: '创作', icon: 'author' },
  { id: 'reviewer', label: '审核', icon: 'review' },
  { id: 'operator', label: '运营', icon: 'config' },
  { id: 'me', label: '我的', icon: 'mine' },
];
