import { Icon } from '../icons/Icon';
import type { WorkspaceConfig } from '../../lib/workspaces';
import type { WorkspaceId } from '../../types';

interface BottomTabProps {
  active: WorkspaceId;
  onSelect: (id: WorkspaceId) => void;
  /** 展示的工作空间（读者默认隐藏审核/运营——UGC-017，App 传入过滤结果）。 */
  workspaces: WorkspaceConfig[];
}

// 移动端底部标签（≤767px）：与顶导同一组工作空间（K-2 已移除书架）。
export function BottomTab({ active, onSelect, workspaces }: BottomTabProps) {
  return (
    <nav className="bottom-tab" aria-label="底部导航">
      {workspaces.map((workspace) => {
        const isActive = active === workspace.id;
        return (
          <button
            key={workspace.id}
            className={'tab' + (isActive ? ' is-active' : '')}
            aria-current={isActive ? 'page' : undefined}
            onClick={() => onSelect(workspace.id)}
          >
            <Icon name={workspace.icon} size={24} />
            <span className="tab__label">{workspace.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
