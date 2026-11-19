import type { WorkspaceConfig } from '../../lib/workspaces';
import type { WorkspaceId } from '../../types';

interface TopNavProps {
  active: WorkspaceId;
  onSelect: (id: WorkspaceId) => void;
  /** 展示的工作空间（读者默认隐藏审核/运营——UGC-017，App 传入过滤结果）。 */
  workspaces: WorkspaceConfig[];
}

// 桌面端顶部导航（≥768px 可见）：5 个工作空间，按 K-1 裁决不设搜索框。
export function TopNav({ active, onSelect, workspaces }: TopNavProps) {
  return (
    <header className="topnav" role="banner">
      <div className="topnav__inner">
        <a
          className="brand"
          href="#catalog"
          onClick={(event) => {
            event.preventDefault();
            onSelect('catalog');
          }}
          aria-label="启明书院 首页"
        >
          <span className="brand__mark" aria-hidden="true">启</span>
          <span className="brand__name">启明书院</span>
        </a>

        <nav className="topnav__nav" aria-label="主导航">
          {workspaces.map((workspace) => (
            <button
              key={workspace.id}
              className={'topnav__link' + (active === workspace.id ? ' is-active' : '')}
              aria-current={active === workspace.id ? 'page' : undefined}
              onClick={() => onSelect(workspace.id)}
            >
              {workspace.label}
            </button>
          ))}
        </nav>
      </div>
    </header>
  );
}
