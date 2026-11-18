import { useState } from 'react';
import type { ReaderSettings } from '../types';
import { TopBar } from '../components/ui';
import { IconCheck, IconMinus, IconPlus } from '../components/Icons';

interface SettingsScreenProps {
  settings: ReaderSettings;
  onChange: (settings: ReaderSettings) => void;
  onBack: () => void;
  onToast: (message: string) => void;
}

const THEMES: Array<{ id: ReaderSettings['theme']; label: string; desc: string; bg: string; ink: string }> = [
  { id: 'paper', label: '纸白', desc: '最接近印刷纸', bg: '#fffdf8', ink: '#1b1916' },
  { id: 'rice', label: '米色', desc: '暖调低对比', bg: '#f4f0e7', ink: '#1b1916' },
  { id: 'green', label: '青绿', desc: '低饱和护眼', bg: '#eaf1e8', ink: '#26342b' },
  { id: 'night', label: '夜间', desc: '暗光环境', bg: '#141312', ink: '#d9d3c8' },
];

export function SettingsScreen({ settings, onChange, onBack, onToast }: SettingsScreenProps) {
  const [preview, setPreview] = useState(true);

  return (
    <div className="m-page m-enter">
      <TopBar title="阅读设置" subtitle="PREFERENCES" onBack={onBack} />

      <section className="m-section">
        <div className="m-section-head">
          <div className="m-section-head__main">
            <p className="m-eyebrow">READING THEME</p>
            <h2 className="m-h2">阅读主题</h2>
          </div>
        </div>
        <div className="m-mode-grid">
          {THEMES.map((theme) => (
            <button
              key={theme.id}
              className={`m-theme-card ${settings.theme === theme.id ? 'is-active' : ''}`.trim()}
              type="button"
              onClick={() => {
                onChange({ ...settings, theme: theme.id });
                onToast(`已切换到${theme.label}`);
              }}
              style={{ background: theme.bg, color: theme.ink }}
              aria-pressed={settings.theme === theme.id}
            >
              <span className="m-theme-card__swatch" style={{ background: theme.ink }} />
              <span className="m-theme-card__label">{theme.label}</span>
              <span className="m-theme-card__desc">{theme.desc}</span>
              {settings.theme === theme.id ? <IconCheck size={16} className="m-theme-card__check" /> : null}
            </button>
          ))}
        </div>
      </section>

      <section className="m-section">
        <div className="m-card m-card--pad">
          <div className="m-sheet__row">
            <span className="m-sheet__label">字号</span>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <button
                className="m-icon-btn"
                type="button"
                onClick={() => onChange({ ...settings, fontSize: Math.max(16, settings.fontSize - 1) })}
                aria-label="减小字号"
              >
                <IconMinus size={18} />
              </button>
              <span className="m-sheet__value" style={{ minWidth: 40, textAlign: 'center' }}>
                {settings.fontSize}px
              </span>
              <button
                className="m-icon-btn"
                type="button"
                onClick={() => onChange({ ...settings, fontSize: Math.min(24, settings.fontSize + 1) })}
                aria-label="增大字号"
              >
                <IconPlus size={18} />
              </button>
            </div>
          </div>
          <div className="m-sheet__row">
            <span className="m-sheet__label">行距</span>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <button
                className="m-icon-btn"
                type="button"
                onClick={() => onChange({ ...settings, lineHeight: Math.max(1.7, Number((settings.lineHeight - 0.05).toFixed(2))) })}
                aria-label="减小行距"
              >
                <IconMinus size={18} />
              </button>
              <span className="m-sheet__value" style={{ minWidth: 40, textAlign: 'center' }}>
                {settings.lineHeight.toFixed(2)}
              </span>
              <button
                className="m-icon-btn"
                type="button"
                onClick={() => onChange({ ...settings, lineHeight: Math.min(2.3, Number((settings.lineHeight + 0.05).toFixed(2))) })}
                aria-label="增大行距"
              >
                <IconPlus size={18} />
              </button>
            </div>
          </div>
          <div className="m-sheet__row">
            <span className="m-sheet__label">字体</span>
            <div style={{ display: 'flex', gap: 6 }}>
              <button
                className="m-chip"
                style={{ minHeight: 32, paddingInline: 10, fontSize: 11 }}
                type="button"
                aria-pressed={settings.fontFamily === 'serif'}
                onClick={() => onChange({ ...settings, fontFamily: 'serif' })}
              >
                宋体
              </button>
              <button
                className="m-chip"
                style={{ minHeight: 32, paddingInline: 10, fontSize: 11 }}
                type="button"
                aria-pressed={settings.fontFamily === 'sans'}
                onClick={() => onChange({ ...settings, fontFamily: 'sans' })}
              >
                黑体
              </button>
            </div>
          </div>
        </div>
      </section>

      <section className="m-section">
        <div className="m-section-head">
          <div className="m-section-head__main">
            <p className="m-eyebrow">PREVIEW</p>
            <h2 className="m-h2">效果预览</h2>
          </div>
          <button className="m-section-head__action" type="button" onClick={() => setPreview((value) => !value)}>
            {preview ? '收起' : '展开'}
          </button>
        </div>
        {preview ? (
          <div
            className="m-card m-card--pad"
            style={{ background: THEMES.find((item) => item.id === settings.theme)?.bg, color: THEMES.find((item) => item.id === settings.theme)?.ink }}
          >
            <p
              style={{
                margin: 0,
                fontFamily: settings.fontFamily === 'serif' ? 'var(--m-font-serif)' : 'var(--m-font-ui)',
                fontSize: settings.fontSize,
                lineHeight: settings.lineHeight,
                textAlign: 'justify',
              }}
            >
              封冻前最后一班船靠岸时，码头上的灯还没有亮。她沿着那道很浅的铅笔痕往下读，忽然发现被略去的一行并不在末尾。
            </p>
          </div>
        ) : null}
      </section>

      <section className="m-section">
        <button
          className="m-btn m-btn--block"
          type="button"
          onClick={() => {
            onChange({ theme: 'rice', fontSize: 19, lineHeight: 1.95, fontFamily: 'serif' });
            onToast('已恢复默认阅读设置');
          }}
        >
          恢复默认
        </button>
      </section>
    </div>
  );
}
