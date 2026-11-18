import type { MobileBook, StudioWork } from '../types';
import { BookCover, StatusBadge, TopBar } from '../components/ui';
import {
  IconChevronRight,
  IconDownload,
  IconPen,
  IconSend,
  IconSparkles,
} from '../components/Icons';

interface WorkScreenProps {
  work: StudioWork;
  book: MobileBook;
  onBack: () => void;
  onOpenEditor: (workId: string, chapterId?: string) => void;
  onOpenAi: () => void;
  onToast: (message: string) => void;
}

export function WorkScreen({ work, book, onBack, onOpenEditor, onOpenAi, onToast }: WorkScreenProps) {
  return (
    <div className="m-page m-enter">
      <TopBar title="作品管理" subtitle="WORKBENCH" onBack={onBack} />

      <section className="m-detail-hero" style={{ margin: '0 -20px', padding: '16px 20px 12px' }}>
        <div className="m-detail-hero__grid">
          <BookCover book={book} className="m-detail-hero__cover" />
          <div className="m-detail-hero__main">
            <p className="m-eyebrow">连载中 · 纪事文学</p>
            <h1 className="m-detail-hero__title">{work.title}</h1>
            <p className="m-detail-hero__author">{work.chapterCount} 章 · {work.words} 字</p>
            <div style={{ marginTop: 10 }}>
              <StatusBadge label="连载中" tone="accent" />
            </div>
          </div>
        </div>
        <div className="m-detail-stats">
          <div className="m-detail-stat">
            <span className="m-detail-stat__value">{work.reads}</span>
            <span className="m-detail-stat__label">阅读</span>
          </div>
          <div className="m-detail-stat">
            <span className="m-detail-stat__value">{work.favorites}</span>
            <span className="m-detail-stat__label">收藏</span>
          </div>
          <div className="m-detail-stat">
            <span className="m-detail-stat__value">{work.revenue}</span>
            <span className="m-detail-stat__label">本月收益</span>
          </div>
        </div>
      </section>

      <div className="m-detail-actions">
        <button className="m-btn m-btn--primary" type="button" onClick={() => onOpenEditor(work.id)}>
          <IconPen size={16} />
          写新章节
        </button>
        <button className="m-btn" type="button" onClick={onOpenAi}>
          <IconSparkles size={16} />
          AI 续写
        </button>
      </div>

      <section className="m-section">
        <div className="m-section-head">
          <div className="m-section-head__main">
            <p className="m-eyebrow">CHAPTERS</p>
            <h2 className="m-h2">章节管理</h2>
          </div>
          <span className="m-badge">{book.chapters.length} 章</span>
        </div>
        <div className="m-chapter-list">
          {book.chapters.map((chapter) => (
            <button
              key={chapter.id}
              className="m-chapter-row"
              type="button"
              onClick={() => onOpenEditor(work.id, chapter.id)}
            >
              <span className="m-chapter-row__no">{String(chapter.no).padStart(2, '0')}</span>
              <span className="m-chapter-row__title">{chapter.title}</span>
              <span className="m-chapter-row__meta">{chapter.words} 字</span>
              <IconChevronRight size={15} />
            </button>
          ))}
        </div>
      </section>

      <section className="m-section">
        <div className="m-card m-card--pad">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
            <div>
              <p className="m-eyebrow">PUBLISH FLOW</p>
              <p className="m-h3" style={{ marginTop: 3 }}>下一章发布</p>
              <p className="m-caption" style={{ marginTop: 4 }}>草稿 → 自检 → 提交审核 → 发布</p>
            </div>
            <button className="m-btn m-btn--sm m-btn--soft" type="button" onClick={() => onToast('发布流程演示：草稿已保存，等待自检')}>
              <IconSend size={15} />
              去发布
            </button>
          </div>
          <hr className="m-rule" style={{ margin: '14px 0' }} />
          <div className="m-sheet__row" style={{ border: 0, padding: 0 }}>
            <span className="m-sheet__label">当前状态</span>
            <StatusBadge label="草稿可编辑" tone="green" />
          </div>
          <div className="m-sheet__row" style={{ border: 0, padding: '8px 0 0' }}>
            <span className="m-sheet__label">最近保存</span>
            <span className="m-sheet__value">今天 09:12</span>
          </div>
        </div>
      </section>

      <section className="m-section">
        <div className="m-section-head">
          <div className="m-section-head__main">
            <p className="m-eyebrow">SETTINGS</p>
            <h2 className="m-h2">作品设置</h2>
          </div>
        </div>
        <div className="m-card m-card--pad">
          {[
            ['作品信息', '书名、简介、标签与封面'],
            ['更新计划', '每周三、周日更新'],
            ['评论管理', '12 条待回复'],
            ['数据导出', '导出章节与阅读数据 CSV'],
          ].map(([title, desc]) => (
            <button
              key={title}
              className="m-row"
              type="button"
              onClick={() => onToast(`${title}将在后续版本开放`)}
            >
              <span className="m-row__main">
                <span className="m-row__title">{title}</span>
                <span className="m-row__meta">{desc}</span>
              </span>
              {title === '数据导出' ? <IconDownload size={16} /> : <IconChevronRight size={16} />}
            </button>
          ))}
        </div>
      </section>

      <p className="m-caption" style={{ textAlign: 'center', marginTop: 20 }}>
        作品数据为本地演示；发布、审核与结算均需服务端契约支持。
      </p>
    </div>
  );
}
