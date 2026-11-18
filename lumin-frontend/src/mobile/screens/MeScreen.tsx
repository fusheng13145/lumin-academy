import type { ReaderSettings } from '../types';
import { MOBILE_BOOKS } from '../data/library';
import { TopBar } from '../components/ui';
import {
  IconChevronRight,
  IconDownload,
  IconHeart,
  IconMessage,
  IconMoon,
  IconSettings,
  IconShare,
  IconSun,
} from '../components/Icons';

interface MeScreenProps {
  theme: 'light' | 'night';
  settings: ReaderSettings;
  onThemeToggle: () => void;
  onOpenSettings: () => void;
  onOpenComments: () => void;
  onOpenBook: (bookId: string) => void;
  onToast: (message: string) => void;
}

function exportLibrary(): void {
  const header = '书名,作者,分类,阅读,收藏,推荐度\n';
  const rows = MOBILE_BOOKS.map((book) =>
    [book.title, book.author, book.category, book.stats.reads, book.stats.favorites, `${book.stats.recommend}%`]
      .map((value) => `"${value.replace(/"/g, '""')}"`)
      .join(',')
  ).join('\n');
  const blob = new Blob([`\uFEFF${header}${rows}`], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `启明书院-书架-${new Date().toISOString().slice(0, 10)}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function MeScreen({
  theme,
  settings,
  onThemeToggle,
  onOpenSettings,
  onOpenComments,
  onOpenBook,
  onToast,
}: MeScreenProps) {
  return (
    <div className="m-page m-enter">
      <TopBar
        title="我的"
        subtitle="ACCOUNT"
        right={
          <button className="m-icon-btn" type="button" onClick={onOpenSettings} aria-label="阅读设置">
            <IconSettings />
          </button>
        }
      />

      <section className="m-profile-hero">
        <div className="m-avatar">林</div>
        <div className="m-profile-hero__main">
          <h1 className="m-profile-hero__name">林砚</h1>
          <p className="m-profile-hero__meta">作者 · 启明书院签约 · 读者号 0042</p>
          <div style={{ display: 'flex', gap: 6, marginTop: 9 }}>
            <span className="m-badge m-badge--accent">连载中</span>
            <span className="m-badge">创作第 3 年</span>
          </div>
        </div>
      </section>

      <div className="m-profile-stats">
        <div className="m-profile-stat">
          <span className="m-profile-stat__v">2</span>
          <span className="m-profile-stat__l">作品</span>
        </div>
        <div className="m-profile-stat">
          <span className="m-profile-stat__v">128.4万</span>
          <span className="m-profile-stat__l">累计阅读</span>
        </div>
        <div className="m-profile-stat">
          <span className="m-profile-stat__v">4,286</span>
          <span className="m-profile-stat__l">本月收益</span>
        </div>
      </div>

      <section className="m-section">
        <div className="m-section-head">
          <div className="m-section-head__main">
            <p className="m-eyebrow">MY SHELF</p>
            <h2 className="m-h2">我的书架</h2>
          </div>
          <span className="m-caption">{MOBILE_BOOKS.slice(0, 2).length} 本</span>
        </div>
        <div className="m-card m-card--pad">
          {MOBILE_BOOKS.slice(0, 2).map((book) => (
            <button
              key={book.id}
              className="m-row"
              type="button"
              onClick={() => onOpenBook(book.id)}
              style={{ minHeight: 74 }}
            >
              <div
                style={{
                  width: 46,
                  height: 64,
                  borderRadius: 8,
                  background: book.cover[0],
                  color: book.cover[1],
                  display: 'grid',
                  placeItems: 'center',
                  fontFamily: 'var(--m-font-serif)',
                  fontSize: 19,
                  fontWeight: 700,
                  flex: 'none',
                }}
                aria-hidden="true"
              >
                {book.title.slice(0, 1)}
              </div>
              <span className="m-row__main">
                <span className="m-row__title">{book.title}</span>
                <span className="m-row__meta">{book.author} · 读至第 1 章</span>
              </span>
              <IconChevronRight size={16} />
            </button>
          ))}
        </div>
      </section>

      <section className="m-section">
        <div className="m-section-head">
          <div className="m-section-head__main">
            <p className="m-eyebrow">SERVICES</p>
            <h2 className="m-h2">作者服务</h2>
          </div>
        </div>
        <div className="m-card m-card--pad">
          <button className="m-row" type="button" onClick={onOpenComments}>
            <span className="m-row__main">
              <span className="m-row__title">评论管理</span>
              <span className="m-row__meta">12 条待回复 · 含 2 条长评</span>
            </span>
            <IconMessage size={18} />
            <IconChevronRight size={15} />
          </button>
          <button
            className="m-row"
            type="button"
            onClick={() => {
              exportLibrary();
              onToast('已生成书架 CSV，请在下载目录查看');
            }}
          >
            <span className="m-row__main">
              <span className="m-row__title">数据导出</span>
              <span className="m-row__meta">书架与阅读数据 CSV</span>
            </span>
            <IconDownload size={18} />
            <IconChevronRight size={15} />
          </button>
          <button className="m-row" type="button" onClick={onOpenSettings}>
            <span className="m-row__main">
              <span className="m-row__title">阅读设置</span>
              <span className="m-row__meta">
                {settings.theme === 'night' ? '夜间' : '纸白'} · {settings.fontSize}px · 宋体
              </span>
            </span>
            <IconSettings size={18} />
            <IconChevronRight size={15} />
          </button>
          <button className="m-row" type="button" onClick={() => onToast('创作权益说明将在 P1 开放')}>
            <span className="m-row__main">
              <span className="m-row__title">创作权益</span>
              <span className="m-row__meta">著作权、AI 来源标记与结算规则</span>
            </span>
            <IconHeart size={18} />
            <IconChevronRight size={15} />
          </button>
          <button className="m-row" type="button" onClick={() => onToast('分享邀请链接已复制')}>
            <span className="m-row__main">
              <span className="m-row__title">邀请读者</span>
              <span className="m-row__meta">把《晚潮记》分享给朋友</span>
            </span>
            <IconShare size={18} />
            <IconChevronRight size={15} />
          </button>
        </div>
      </section>

      <section className="m-section">
        <div className="m-card m-card--pad">
          <button className="m-row" type="button" onClick={onThemeToggle} style={{ border: 0 }}>
            <span className="m-row__main">
              <span className="m-row__title">外观</span>
              <span className="m-row__meta">{theme === 'night' ? '夜间模式' : '日间模式'} · 跟随阅读主题独立</span>
            </span>
            {theme === 'night' ? <IconMoon size={19} /> : <IconSun size={19} />}
            <span className="m-badge">{theme === 'night' ? '暗' : '亮'}</span>
          </button>
        </div>
      </section>

      <footer className="m-section" style={{ paddingBottom: 10 }}>
        <p className="m-caption" style={{ textAlign: 'center' }}>
          启明书院 · Android Edition
          <br />
          v0.1.0 · 本地演示构建
        </p>
      </footer>
    </div>
  );
}
