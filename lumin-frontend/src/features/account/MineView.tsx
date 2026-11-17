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
    </section>
  );
}
