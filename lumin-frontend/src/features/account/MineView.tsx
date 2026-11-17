import { useEffect, useState } from 'react';
import { BAND_LABEL } from '../../lib/demoPolicy';
import { Icon } from '../../components/icons/Icon';
import type { AgeBand } from '../../types';

interface MineViewProps {
  uiTheme: 'light' | 'night';
  onToggleUiTheme: () => void;
  ageBand: AgeBand;
  onAgeBandChange: (band: AgeBand) => void;
}

const AGE_BANDS: AgeBand[] = ['C0', 'C1', 'C2', 'C3', 'C4'];

// 我的：界面偏好与年龄带（演示评审开关）
export function MineView({
  uiTheme,
  onToggleUiTheme,
  ageBand,
  onAgeBandChange,
}: MineViewProps) {
  return (
    <section aria-labelledby="mine-h">
      <header className="page-head">
        <h1 id="mine-h" tabIndex={-1}>我的</h1>
      </header>

      <div className="card setting-block">
        <div className="setting-row">
          <span>夜间模式</span>
          <button
            className={'switch' + (uiTheme === 'night' ? ' is-on' : '')}
            role="switch"
            aria-checked={uiTheme === 'night'}
            onClick={onToggleUiTheme}
          >
            <span className="switch__thumb">
              <Icon name={uiTheme === 'night' ? 'moon' : 'sun'} size={14} />
            </span>
          </button>
        </div>

        <div className="setting-row">
          <span>年龄带</span>
          <select
            className="select"
            value={ageBand}
            onChange={(event) => onAgeBandChange(event.target.value as AgeBand)}
            aria-label="选择年龄带"
          >
            {AGE_BANDS.map((band) => (
              <option key={band} value={band}>{BAND_LABEL[band]}</option>
            ))}
          </select>
        </div>
      </div>

      <div className="card setting-block">
        <h2 className="setting-block__title">
          <Icon name="bell" size={18} /> 通知
        </h2>
        <NotificationList />
      </div>
    </section>
  );
}

function NotificationList() {
  const [items, setItems] = useState<
    { id: string; category: string; title: string }[]
  >([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch('/api/v1/me/notifications')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((d) => setItems(d.data))
      .catch((e) => setError(e instanceof Error ? e.message : '加载失败'));
  }, []);

  if (error) {
    return <p className="muted-note">{error}</p>;
  }
  if (items.length === 0) {
    return <p className="muted-note">暂无通知</p>;
  }
  return (
    <ul className="notice-list">
      {items.map((n) => (
        <li key={n.id} className="notice notice--info">
          <span className="badge badge--neutral">{n.category}</span>
          <p>{n.title}</p>
        </li>
      ))}
    </ul>
  );
}
