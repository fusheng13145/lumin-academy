import type { CSSProperties, ReactNode } from 'react';
import { useEffect } from 'react';
import type { MobileBook } from '../types';
import type { MobileTab } from '../navigation';
import { TAB_LABEL } from '../navigation';
import {
  IconBack,
  IconBook,
  IconClose,
  IconPen,
  IconSparkles,
  IconUser,
} from './Icons';

type CoverVars = CSSProperties & {
  '--cover-bg'?: string;
  '--cover-ink'?: string;
};

export interface CoverProps {
  title: string;
  kicker?: string;
  author?: string;
  cover: [string, string];
  mark?: string;
  className?: string;
  wide?: boolean;
}

export function Cover({
  title,
  kicker = 'LUMIN ACADEMY',
  author,
  cover,
  mark,
  className = '',
  wide = false,
}: CoverProps) {
  const style: CoverVars = {
    '--cover-bg': cover[0],
    '--cover-ink': cover[1],
  };

  return (
    <div className={`m-cover ${wide ? 'm-cover--wide' : ''} ${className}`.trim()} style={style}>
      <span className="m-cover__mark" aria-hidden="true">
        {mark ?? title.slice(0, 1)}
      </span>
      <div className="m-cover__content">
        <span className="m-cover__kicker">{kicker}</span>
        <h3 className="m-cover__title">{title}</h3>
        <span className="m-cover__author">{author ?? '启明书院'}</span>
      </div>
    </div>
  );
}

export function BookCover({ book, className = '' }: { book: MobileBook; className?: string }) {
  return (
    <Cover
      title={book.title}
      author={book.author}
      cover={book.cover}
      mark={book.title.slice(0, 1)}
      className={className}
    />
  );
}

export function TopBar({
  title,
  subtitle,
  onBack,
  right,
  scrolled = false,
}: {
  title: string;
  subtitle?: string;
  onBack?: () => void;
  right?: ReactNode;
  scrolled?: boolean;
}) {
  return (
    <header className={`m-topbar ${scrolled ? 'is-scrolled' : ''}`.trim()}>
      <div className="m-topbar__inner">
        {onBack ? (
          <button className="m-icon-btn" type="button" onClick={onBack} aria-label="返回">
            <IconBack />
          </button>
        ) : (
          <span className="m-brand__mark" aria-hidden="true">
            启
          </span>
        )}
        <div className="m-topbar__title">
          {subtitle ? <span className="m-topbar__subtitle">{subtitle}</span> : null}
          {title}
        </div>
        {right ?? <span style={{ width: 44 }} aria-hidden="true" />}
      </div>
    </header>
  );
}

export function SectionHead({
  title,
  eyebrow,
  action,
  onAction,
}: {
  title: string;
  eyebrow?: string;
  action?: string;
  onAction?: () => void;
}) {
  return (
    <div className="m-section-head">
      <div className="m-section-head__main">
        {eyebrow ? <p className="m-eyebrow">{eyebrow}</p> : null}
        <h2 className="m-h2">{title}</h2>
      </div>
      {action && onAction ? (
        <button className="m-section-head__action" type="button" onClick={onAction}>
          {action}
        </button>
      ) : null}
    </div>
  );
}

export function Sheet({
  open,
  onClose,
  title,
  children,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
}) {
  useEffect(() => {
    if (!open) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, onClose]);

  if (!open) {
    return null;
  }

  return (
    <>
      <div className="m-overlay" onClick={onClose} aria-hidden="true" />
      <div className="m-sheet" role="dialog" aria-modal="true" aria-label={title}>
        <div className="m-sheet__grip" aria-hidden="true" />
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <h2 className="m-sheet__title">{title}</h2>
          <button className="m-icon-btn" type="button" onClick={onClose} aria-label="关闭">
            <IconClose size={18} />
          </button>
        </div>
        {children}
      </div>
    </>
  );
}

export function Toast({ message }: { message: string | null }) {
  if (!message) {
    return null;
  }
  return (
    <div className="m-toast" role="status" aria-live="polite">
      {message}
    </div>
  );
}

export function BottomNav({
  active,
  onSelect,
}: {
  active: MobileTab;
  onSelect: (tab: MobileTab) => void;
}) {
  const tabs: Array<{ id: MobileTab; icon: ReactNode }> = [
    { id: 'home', icon: <IconBook /> },
    { id: 'studio', icon: <IconPen /> },
    { id: 'ai', icon: <IconSparkles /> },
    { id: 'me', icon: <IconUser /> },
  ];

  return (
    <nav className="m-bottom-nav" aria-label="主导航">
      <div className="m-bottom-nav__inner">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            type="button"
            className={`m-tab ${active === tab.id ? 'is-active' : ''}`.trim()}
            onClick={() => onSelect(tab.id)}
            aria-current={active === tab.id ? 'page' : undefined}
          >
            {tab.icon}
            <span className="m-tab__label">{TAB_LABEL[tab.id]}</span>
          </button>
        ))}
      </div>
    </nav>
  );
}

export function StatusBadge({ label, tone = 'neutral' }: { label: string; tone?: 'neutral' | 'accent' | 'green' }) {
  return <span className={`m-badge ${tone === 'neutral' ? '' : `m-badge--${tone}`}`.trim()}>{label}</span>;
}
