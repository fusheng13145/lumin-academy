import { Icon } from '../icons/Icon';
import { WORKSPACES } from '../../mocks/mock';
import type { WorkspaceId } from '../../types';

interface BottomTabProps {
  active: WorkspaceId;
  onSelect: (id: WorkspaceId) => void;
}

// 移动端底部标签（≤767px）：与顶导同一组 5 个工作空间（K-2 已移除书架）。
export function BottomTab({ active, onSelect }: BottomTabProps) {
  return (
    <nav className="bottom-tab" aria-label="底部导航">
      {WORKSPACES.map((workspace) => {
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
