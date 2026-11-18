import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import type { MobileBook, ReaderSettings } from '../types';
import { Sheet, Toast } from '../components/ui';
import {
  IconBack,
  IconCheck,
  IconList,
  IconMessage,
  IconMinus,
  IconMoon,
  IconMore,
  IconPlus,
  IconSettings,
  IconSun,
} from '../components/Icons';

interface ReaderScreenProps {
  book: MobileBook;
  chapterNo: number;
  settings: ReaderSettings;
  onBack: () => void;
  onChapterChange: (chapterNo: number) => void;
  onSettingsChange: (settings: ReaderSettings) => void;
  onProgress: (chapterNo: number, percent: number) => void;
}

const THEMES: Array<{ id: ReaderSettings['theme']; label: string }> = [
  { id: 'paper', label: '纸白' },
  { id: 'rice', label: '米色' },
  { id: 'green', label: '青绿' },
  { id: 'night', label: '夜间' },
];

export function ReaderScreen({
  book,
  chapterNo,
  settings,
  onBack,
  onChapterChange,
  onSettingsChange,
  onProgress,
}: ReaderScreenProps) {
  const [catalogOpen, setCatalogOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const chapter = book.chapters.find((item) => item.no === chapterNo) ?? book.chapters[0];
  const total = book.chapters.length;
  const progressRef = useRef(onProgress);

  useEffect(() => {
    progressRef.current = onProgress;
  }, [onProgress]);

  useEffect(() => {
    document.documentElement.dataset.readTheme = settings.theme;
    return () => {
      delete document.documentElement.dataset.readTheme;
    };
  }, [settings.theme]);

  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'auto' });
    progressRef.current(chapter.no, Math.round((chapter.no / total) * 100));
  }, [chapter.no, total]);

  useEffect(() => {
    if (!toast) {
      return;
    }
    const timer = window.setTimeout(() => setToast(null), 2200);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const readerStyle = {
    '--reader-font': `${settings.fontSize}px`,
    '--reader-line': String(settings.lineHeight),
    fontFamily: settings.fontFamily === 'serif' ? 'var(--m-font-serif)' : 'var(--m-font-ui)',
  } as CSSProperties;

  return (
    <div className="m-reader">
      <header className="m-reader__bar">
        <div className="m-reader__bar-inner">
          <button className="m-icon-btn" type="button" onClick={onBack} aria-label="返回">
            <IconBack />
          </button>
          <div className="m-reader__bar-title">
            {book.title} · {chapter.title}
          </div>
          <button
            className="m-icon-btn"
            type="button"
            onClick={() => setCatalogOpen(true)}
            aria-label="打开目录"
          >
            <IconMore />
          </button>
        </div>
        <div className="m-reader__progress" aria-hidden="true">
          <span style={{ width: `${Math.round((chapter.no / total) * 100)}%` }} />
        </div>
      </header>

      <article className="m-reader__body">
        <p className="m-reader__chapter-no">
          CHAPTER {String(chapter.no).padStart(2, '0')} / {String(total).padStart(2, '0')}
        </p>
        <h1 className="m-reader__title">{chapter.title}</h1>
        <div className="m-reader__content" style={readerStyle}>
          {chapter.body.map((paragraph, index) => (
            <p key={`${chapter.id}-p${index}`}>{paragraph}</p>
          ))}
        </div>

        <div className="m-reader__foot">
          <button
            className="m-btn m-btn--sm"
            type="button"
            disabled={chapter.no <= 1}
            onClick={() => onChapterChange(chapter.no - 1)}
          >
            上一章
          </button>
          <button
            className="m-btn m-btn--sm m-btn--primary"
            type="button"
            disabled={chapter.no >= total}
            onClick={() => onChapterChange(chapter.no + 1)}
          >
            下一章
          </button>
        </div>
        <p className="m-caption" style={{ textAlign: 'center', marginTop: 28 }}>
          本章完 · 启明书院
        </p>
      </article>

      <div className="m-reader__dock">
        <div className="m-reader__dock-inner">
          <button
            className="m-reader__dock-btn"
            type="button"
            onClick={() => setCatalogOpen(true)}
          >
            <IconList />
            目录
          </button>
          <button
            className="m-reader__dock-btn"
            type="button"
            onClick={() => {
              const next = settings.theme === 'night' ? 'paper' : 'night';
              onSettingsChange({ ...settings, theme: next });
            }}
          >
            {settings.theme === 'night' ? <IconSun /> : <IconMoon />}
            主题
          </button>
          <button
            className="m-reader__dock-btn"
            type="button"
            onClick={() => setSettingsOpen(true)}
          >
            <IconSettings />
            设置
          </button>
          <button
            className="m-reader__dock-btn"
            type="button"
            onClick={() => setToast('评论与段评将在 P1 开放')}
          >
            <IconMessage />
            评论
          </button>
        </div>
      </div>

      <Sheet open={catalogOpen} onClose={() => setCatalogOpen(false)} title="目录">
        <p className="m-caption" style={{ marginBottom: 8 }}>
          共 {total} 章 · 当前第 {chapter.no} 章
        </p>
        <div className="m-chapter-list">
          {book.chapters.map((item) => (
            <button
              key={item.id}
              className="m-chapter-row"
              type="button"
              onClick={() => {
                onChapterChange(item.no);
                setCatalogOpen(false);
              }}
            >
              <span className="m-chapter-row__no">{String(item.no).padStart(2, '0')}</span>
              <span className="m-chapter-row__title" style={{ fontWeight: item.no === chapter.no ? 700 : 400 }}>
                {item.title}
              </span>
              <span className="m-chapter-row__meta">{item.words} 字</span>
              {item.no === chapter.no ? <IconCheck size={16} /> : null}
            </button>
          ))}
        </div>
      </Sheet>

      <Sheet open={settingsOpen} onClose={() => setSettingsOpen(false)} title="阅读设置">
        <div className="m-sheet__row">
          <span className="m-sheet__label">阅读主题</span>
          <div style={{ display: 'flex', gap: 6 }}>
            {THEMES.map((theme) => (
              <button
                key={theme.id}
                className="m-chip"
                style={{ minHeight: 32, paddingInline: 10, fontSize: 11 }}
                type="button"
                aria-pressed={settings.theme === theme.id}
                onClick={() => onSettingsChange({ ...settings, theme: theme.id })}
              >
                {theme.label}
              </button>
            ))}
          </div>
        </div>
        <div className="m-sheet__row">
          <span className="m-sheet__label">字号</span>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <button
              className="m-icon-btn"
              type="button"
              onClick={() => onSettingsChange({ ...settings, fontSize: Math.max(16, settings.fontSize - 1) })}
              aria-label="减小字号"
            >
              <IconMinus size={18} />
            </button>
            <span className="m-sheet__value" style={{ minWidth: 34, textAlign: 'center' }}>
              {settings.fontSize}px
            </span>
            <button
              className="m-icon-btn"
              type="button"
              onClick={() => onSettingsChange({ ...settings, fontSize: Math.min(24, settings.fontSize + 1) })}
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
              onClick={() => onSettingsChange({ ...settings, lineHeight: Math.max(1.7, Number((settings.lineHeight - 0.05).toFixed(2))) })}
              aria-label="减小行距"
            >
              <IconMinus size={18} />
            </button>
            <span className="m-sheet__value" style={{ minWidth: 34, textAlign: 'center' }}>
              {settings.lineHeight.toFixed(2)}
            </span>
            <button
              className="m-icon-btn"
              type="button"
              onClick={() => onSettingsChange({ ...settings, lineHeight: Math.min(2.3, Number((settings.lineHeight + 0.05).toFixed(2))) })}
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
              onClick={() => onSettingsChange({ ...settings, fontFamily: 'serif' })}
            >
              宋体
            </button>
            <button
              className="m-chip"
              style={{ minHeight: 32, paddingInline: 10, fontSize: 11 }}
              type="button"
              aria-pressed={settings.fontFamily === 'sans'}
              onClick={() => onSettingsChange({ ...settings, fontFamily: 'sans' })}
            >
              黑体
            </button>
          </div>
        </div>
        <div
          className="m-card m-card--pad"
          style={{ marginTop: 14, background: 'var(--read-bg)', color: 'var(--read-ink)' }}
        >
          <p className="m-caption" style={{ color: 'inherit', opacity: 0.6 }}>
            预览
          </p>
          <p
            style={{
              margin: '7px 0 0',
              fontFamily: settings.fontFamily === 'serif' ? 'var(--m-font-serif)' : 'var(--m-font-ui)',
              fontSize: settings.fontSize,
              lineHeight: settings.lineHeight,
            }}
          >
            潮水退去之后，堤上的第三盏灯仍旧没有亮。
          </p>
        </div>
      </Sheet>

      <Toast message={toast} />
    </div>
  );
}
