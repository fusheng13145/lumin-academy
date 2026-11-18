import { useState } from 'react';
import { STUDIO_METRICS, STUDIO_TASKS, STUDIO_WORKS } from '../data/studio';
import { StatusBadge, TopBar } from '../components/ui';
import {
  IconArrowUpRight,
  IconBrain,
  IconCalendar,
  IconChevronRight,
  IconMessage,
  IconPen,
  IconTrend,
  IconLibrary,
} from '../components/Icons';

interface StudioScreenProps {
  onOpenWork: (workId: string) => void;
  onOpenEditor: (workId: string) => void;
  onOpenAi: () => void;
  onComments: () => void;
  onMaterials: () => void;
  onToast: (message: string) => void;
}

type Range = 'day' | 'week' | 'month';

const SERIES: Record<Range, { labels: string[]; values: number[]; total: string; delta: string }> = {
  day: {
    labels: ['00', '04', '08', '12', '16', '20', '24'],
    values: [12, 8, 26, 42, 36, 54, 48],
    total: '1,284',
    delta: '+12.6%',
  },
  week: {
    labels: ['一', '二', '三', '四', '五', '六', '日'],
    values: [42, 58, 46, 72, 65, 88, 76],
    total: '8,426',
    delta: '+8.2%',
  },
  month: {
    labels: ['W1', 'W2', 'W3', 'W4'],
    values: [180, 240, 218, 286],
    total: '36,120',
    delta: '+18.4%',
  },
};

const RANGE_LABEL: Record<Range, string> = { day: '日', week: '周', month: '月' };

export function StudioScreen({
  onOpenWork,
  onOpenEditor,
  onOpenAi,
  onComments,
  onMaterials,
  onToast,
}: StudioScreenProps) {
  const [range, setRange] = useState<Range>('week');
  const series = SERIES[range];
  const max = Math.max(...series.values);
  const dateLabel = new Date().toLocaleDateString('zh-CN', {
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  });

  return (
    <div className="m-page m-enter">
      <TopBar
        title="作者工作室"
        subtitle="AUTHOR STUDIO"
        right={
          <button className="m-icon-btn" type="button" onClick={() => onToast('工作室设置将在 P1 开放')} aria-label="工作室设置">
            <IconCalendar />
          </button>
        }
      />

      <div className="m-studio-head">
        <div>
          <p className="m-eyebrow">{dateLabel}</p>
          <h1 className="m-h1" style={{ marginTop: 6 }}>
            林砚，今天继续写。
          </h1>
          <p className="m-studio-head__meta">《晚潮记》连载中 · 本周热度上升 6 位</p>
        </div>
      </div>

      <div className="m-metrics">
        {STUDIO_METRICS.map((metric) => (
          <div className="m-metric" key={metric.label}>
            <span className="m-metric__value">
              {metric.value}
              <small>{metric.unit}</small>
            </span>
            <span className="m-metric__label">{metric.label}</span>
            <span className="m-caption" style={{ color: 'var(--m-green)', marginTop: 4, display: 'block' }}>
              {metric.delta}
            </span>
          </div>
        ))}
      </div>

      <section className="m-section">
        <div className="m-section-head">
          <div className="m-section-head__main">
            <p className="m-eyebrow">READING TREND</p>
            <h2 className="m-h2">阅读趋势</h2>
          </div>
          <div style={{ display: 'flex', gap: 4 }}>
            {(Object.keys(RANGE_LABEL) as Range[]).map((item) => (
              <button
                key={item}
                className="m-chip"
                style={{ minHeight: 30, paddingInline: 10, fontSize: 11 }}
                type="button"
                aria-pressed={range === item}
                onClick={() => setRange(item)}
              >
                {RANGE_LABEL[item]}
              </button>
            ))}
          </div>
        </div>
        <div className="m-card m-card--pad">
          <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between' }}>
            <div>
              <span className="m-metric__value" style={{ fontSize: 28 }}>
                {series.total}
              </span>
              <span className="m-caption" style={{ marginLeft: 8 }}>
                阅读人次 · {RANGE_LABEL[range]}
              </span>
            </div>
            <span className="m-badge m-badge--green">{series.delta}</span>
          </div>
          <div className="m-trend" aria-label={`${RANGE_LABEL[range]}阅读趋势`}>
            <div className="m-bars">
              {series.values.map((value, index) => (
                <div className="m-bar-col" key={`${range}-${series.labels[index]}`}>
                  <div className="m-bar" style={{ height: `${Math.max(8, (value / max) * 100)}%` }} />
                  <span className="m-bar-label">{series.labels[index]}</span>
                </div>
              ))}
            </div>
          </div>
          <p className="m-caption" style={{ marginTop: 8 }}>
            <IconTrend size={12} style={{ verticalAlign: 'middle', marginRight: 4 }} />
            数据来自本地演示口径；真实环境接入作者数据看板接口。
          </p>
        </div>
      </section>

      <section className="m-section">
        <div className="m-card" style={{ display: 'flex', alignItems: 'center', gap: 12, padding: 14 }}>
          <div style={{ width: 34, height: 34, borderRadius: 10, background: 'var(--m-ink)', color: 'var(--m-paper)', display: 'grid', placeItems: 'center', flex: 'none' }}>
            <IconTrend size={18} />
          </div>
          <div style={{ flex: 1 }}>
            <p className="m-eyebrow">热度排名</p>
            <p className="m-h3" style={{ marginTop: 2 }}>
              本周新书榜 · 第 12 名
            </p>
          </div>
          <button className="m-btn m-btn--sm" type="button" onClick={() => onToast('完整榜单将在运营端开放')}>
            查看
            <IconArrowUpRight size={14} />
          </button>
        </div>
      </section>

      <section className="m-section">
        <div className="m-section-head">
          <div className="m-section-head__main">
            <p className="m-eyebrow">TODAY'S TASKS</p>
            <h2 className="m-h2">待办</h2>
          </div>
          <span className="m-badge m-badge--accent">{STUDIO_TASKS.length} 项</span>
        </div>
        <div className="m-card">
          {STUDIO_TASKS.map((task) => (
            <div className="m-task-card" key={task.id}>
              <div className="m-task-card__icon">
                {task.target === 'ai' ? <IconBrain size={18} /> : task.target === 'comments' ? <IconMessage size={18} /> : <IconPen size={18} />}
              </div>
              <div className="m-task-card__main">
                <p className="m-task-card__title">{task.title}</p>
                <p className="m-task-card__meta">{task.meta}</p>
              </div>
              <button
                className="m-btn m-btn--sm m-btn--soft"
                type="button"
                onClick={() => {
                  if (task.target === 'ai') {
                    onOpenAi();
                    return;
                  }
                  if (task.target === 'comments') {
                    onComments();
                    return;
                  }
                  onOpenEditor('w1');
                }}
              >
                {task.actionLabel}
              </button>
            </div>
          ))}
        </div>
      </section>

      <section className="m-section">
        <button className="m-card m-card--pad" type="button" onClick={onMaterials} style={{ width: '100%', display: 'flex', alignItems: 'center', gap: 12, textAlign: 'left', color: 'inherit' }}>
          <div style={{ width: 36, height: 36, borderRadius: 10, background: 'var(--m-paper)', border: '1px solid var(--m-line)', display: 'grid', placeItems: 'center', flex: 'none' }}>
            <IconLibrary size={18} />
          </div>
          <div style={{ flex: 1 }}>
            <p className="m-eyebrow">MATERIALS</p>
            <p className="m-h3" style={{ marginTop: 2 }}>素材库与灵感</p>
            <p className="m-caption" style={{ marginTop: 2 }}>取材、记灵感，再进入写作或 AI 创作台。</p>
          </div>
          <span className="m-badge">打开</span>
        </button>
      </section>

      <section className="m-section">
        <div className="m-section-head">
          <div className="m-section-head__main">
            <p className="m-eyebrow">MY WORKS</p>
            <h2 className="m-h2">我的作品</h2>
          </div>
          <button className="m-section-head__action" type="button" onClick={() => onToast('新建作品将在 P1 开放')}>
            + 新建
          </button>
        </div>
        <div className="m-card m-card--pad">
          {STUDIO_WORKS.map((work) => (
            <div className="m-work-card" key={work.id}>
              <div
                style={{
                  aspectRatio: '3 / 4.25',
                  borderRadius: 10,
                  background: work.cover[0],
                  color: work.cover[1],
                  display: 'grid',
                  placeItems: 'center',
                  fontFamily: 'var(--m-font-serif)',
                  fontSize: 24,
                  fontWeight: 700,
                  boxShadow: '0 10px 24px -18px rgba(25,24,22,.5)',
                }}
                aria-hidden="true"
              >
                {work.title.slice(0, 1)}
              </div>
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  <h3 className="m-work-card__title">{work.title}</h3>
                  <StatusBadge label={work.statusLabel} tone={work.status === 'serial' ? 'accent' : 'neutral'} />
                </div>
                <p className="m-work-card__meta">
                  {work.subtitle} · {work.chapterCount} 章 · {work.words} 字 · 更新于 {work.updatedAt}
                </p>
                <div className="m-work-card__actions">
                  <button className="m-btn m-btn--sm" type="button" onClick={() => onOpenWork(work.id)}>
                    管理作品
                    <IconChevronRight size={14} />
                  </button>
                  {work.status === 'serial' ? (
                    <button className="m-btn m-btn--sm m-btn--soft" type="button" onClick={() => onOpenEditor(work.id)}>
                      继续写
                    </button>
                  ) : null}
                </div>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="m-section">
        <div className="m-card m-card--pad" style={{ background: 'var(--m-ink)', color: 'var(--m-paper)' }}>
          <p className="m-eyebrow" style={{ color: 'rgba(244,240,231,.55)' }}>
            CREATOR FUND
          </p>
          <h2 className="m-h2" style={{ color: 'var(--m-paper)', marginTop: 4 }}>
            本月可提现 ¥4,286
          </h2>
          <p style={{ margin: '7px 0 14px', fontSize: 12, color: 'rgba(244,240,231,.68)' }}>
            收益按自然月结算；数据为演示口径，真实环境对接结算中心。
          </p>
          <button className="m-btn m-btn--sm" type="button" onClick={() => onToast('提现流程将在 P1 结算模块开放')} style={{ borderColor: 'rgba(244,240,231,.28)', color: 'var(--m-paper)' }}>
            查看收益明细
          </button>
        </div>
      </section>
    </div>
  );
}
