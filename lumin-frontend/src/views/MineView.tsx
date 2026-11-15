import { Icon } from '../components/Icon';
import { AUDITS } from '../data/mock';
import { BAND_LABEL, POLICY_HINT } from '../lib/demoPolicy';
import type { AgeBand, NoticeLevel } from '../types';

interface MineViewProps {
  uiTheme: 'light' | 'night';
  onToggleUiTheme: () => void;
  ageBand: AgeBand;
  onAgeBandChange: (band: AgeBand) => void;
}

const AGE_BANDS: AgeBand[] = ['C0', 'C1', 'C2', 'C3', 'C4'];

const LEVEL_CLS: Record<NoticeLevel, string> = {
  info: 'notice--info',
  warning: 'notice--warning',
  danger: 'notice--danger',
};

const LEVEL_TEXT: Record<NoticeLevel, string> = {
  info: '通知',
  warning: '警示',
  danger: '严重',
};

// 我的：夜间开关、年龄带演示、治理公示（info/warning/danger）与举报申诉入口
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
        <p className="page-head__sub">阅读偏好、年龄带演示与平台治理公示</p>
      </header>

      <div className="card setting-block">
        <h2 className="setting-block__title">
          <Icon name="settings" size={18} /> 阅读与界面设置
        </h2>

        <div className="setting-row">
          <span>界面夜间模式</span>
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
          <span>演示年龄带（评审用，仅影响书城灰显）</span>
          <select
            className="select"
            value={ageBand}
            onChange={(event) => onAgeBandChange(event.target.value as AgeBand)}
            aria-label="选择演示年龄带"
          >
            {AGE_BANDS.map((band) => (
              <option key={band} value={band}>{BAND_LABEL[band]}</option>
            ))}
          </select>
        </div>
        <p className="setting-hint">
          年龄带由服务端 AgePolicy 裁决；{POLICY_HINT}
        </p>
      </div>

      <div className="card setting-block" id="me-governance">
        <h2 className="setting-block__title">
          <Icon name="bell" size={18} /> 治理公示（页脚锚点目标）
        </h2>
        <div className="notice-list">
          {AUDITS.map((notice) => (
            <div className={'notice ' + LEVEL_CLS[notice.level]} key={notice.id}>
              <span className={'badge badge--' + (notice.level === 'info' ? 'neutral' : notice.level === 'warning' ? 'review' : 'removed')}>
                {LEVEL_TEXT[notice.level]}
              </span>
              <p>{notice.text}</p>
              <span className="notice__time">{notice.time}</span>
            </div>
          ))}
        </div>
      </div>

      <div className="card setting-block">
        <h2 className="setting-block__title">
          <Icon name="report" size={18} /> 举报与申诉
        </h2>
        <p className="setting-hint">
          发现违规内容可发起举报；被下架作品可走申诉通道。治理流程由平台按四眼原则裁决。
        </p>
        <div className="toolbar-row">
          <button className="btn btn--soft" type="button">
            <Icon name="report" size={18} /> 发起举报
          </button>
          <button className="btn btn--ghost" type="button">
            <Icon name="review" size={18} /> 查看我的申诉
          </button>
        </div>
      </div>
    </section>
  );
}
