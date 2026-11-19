import type { IconName } from '../components/icons/Icon';
import type { WorkspaceId } from '../types';

/** 工作空间导航配置；Web 壳与共享布局共用，不依赖 App。 */
export interface WorkspaceConfig {
  id: WorkspaceId;
  label: string;
  icon: IconName;
  /** 默认（读者）导航是否展示；审核/运营为工作台入口——读者导航默认隐藏，
   *  经「我的 → 显示审核/运营入口」开关或深链直达（服务端权限不受菜单隐藏影响）。 */
  defaultNav: boolean;
}

export const WORKSPACES: WorkspaceConfig[] = [
  { id: 'catalog', label: '书城', icon: 'catalog', defaultNav: true },
  { id: 'author', label: '创作', icon: 'author', defaultNav: true },
  { id: 'reviewer', label: '审核', icon: 'review', defaultNav: false },
  { id: 'operator', label: '运营', icon: 'config', defaultNav: false },
  { id: 'me', label: '我的', icon: 'mine', defaultNav: true },
];
