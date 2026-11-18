import { useState } from 'react';
import { Sheet, TopBar } from '../components/ui';
import { IconDownload, IconRefresh, IconSparkles, IconWand } from '../components/Icons';

interface IllustrationScreenProps {
  onBack: () => void;
  onToast: (message: string) => void;
}

type ArtStyle = 'ink' | 'woodcut' | 'cinema';
type ArtRatio = '3:4' | '1:1' | '16:9';

const STYLES: Array<{ id: ArtStyle; label: string; desc: string }> = [
  { id: 'ink', label: '水墨', desc: '留白、淡墨、朱砂点' },
  { id: 'woodcut', label: '木刻', desc: '硬边、高对比、纸纹' },
  { id: 'cinema', label: '电影感', desc: '低饱和、侧光、颗粒' },
];

const RATIOS: Array<{ id: ArtRatio; label: string }> = [
  { id: '3:4', label: '3:4 封面' },
  { id: '1:1', label: '1:1 方图' },
  { id: '16:9', label: '16:9 横图' },
];

export function IllustrationScreen({ onBack, onToast }: IllustrationScreenProps) {
  const [prompt, setPrompt] = useState('封冻前的码头，最后一班船靠岸，堤上第三盏灯没有亮；远处有雾，近处只有缆绳和水光。');
  const [style, setStyle] = useState<ArtStyle>('ink');
  const [ratio, setRatio] = useState<ArtRatio>('3:4');
  const [generated, setGenerated] = useState(true);
  const [busy, setBusy] = useState(false);
  const [sheet, setSheet] = useState(false);

  function generate(): void {
    if (busy) {
      return;
    }
    setBusy(true);
    setGenerated(false);
    window.setTimeout(() => {
      setBusy(false);
      setGenerated(true);
      onToast('概念图已生成（本地演示，不调用图像模型）');
    }, 900);
  }

  return (
    <div className="m-page m-enter">
      <TopBar title="AI 插图" subtitle="VISUAL" onBack={onBack} right={<span className="m-badge">本地演示</span>} />

      <section className="m-section" style={{ marginTop: 8 }}>
        <div className="m-section-head">
          <div className="m-section-head__main">
            <p className="m-eyebrow">SCENE BRIEF</p>
            <h2 className="m-h2">想画哪一幕</h2>
          </div>
        </div>
        <textarea
          className="m-ai-prompt"
          value={prompt}
          onChange={(event) => setPrompt(event.target.value)}
          placeholder="描述场景、人物、光线与情绪"
          aria-label="插图描述"
        />
        <p className="m-ai-hint">
          真实环境由服务端调用图像模型；本地构建只用 CSS 生成一张可交互的概念图，不伪装成 AI 结果。
        </p>

        <div className="m-section-head" style={{ marginTop: 18 }}>
          <div className="m-section-head__main">
            <p className="m-eyebrow">STYLE</p>
            <h2 className="m-h2">风格</h2>
          </div>
        </div>
        <div className="m-memory" style={{ gap: 8 }}>
          {STYLES.map((item) => (
            <button
              key={item.id}
              className="m-chip"
              type="button"
              aria-pressed={style === item.id}
              onClick={() => setStyle(item.id)}
            >
              {item.label}
            </button>
          ))}
        </div>
        <p className="m-caption" style={{ marginTop: 4 }}>
          {STYLES.find((item) => item.id === style)?.desc}
        </p>

        <div className="m-section-head" style={{ marginTop: 18 }}>
          <div className="m-section-head__main">
            <p className="m-eyebrow">RATIO</p>
            <h2 className="m-h2">比例</h2>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 6 }}>
          {RATIOS.map((item) => (
            <button
              key={item.id}
              className="m-chip"
              type="button"
              aria-pressed={ratio === item.id}
              onClick={() => setRatio(item.id)}
            >
              {item.label}
            </button>
          ))}
        </div>

        <button className="m-btn m-btn--primary m-btn--block" type="button" onClick={generate} disabled={busy} style={{ marginTop: 18 }}>
          <IconWand size={17} />
          {busy ? '正在生成概念图…' : '生成概念图'}
        </button>
      </section>

      <section className="m-section">
        <div className="m-section-head">
          <div className="m-section-head__main">
            <p className="m-eyebrow">PREVIEW</p>
            <h2 className="m-h2">概念图预览</h2>
          </div>
          {generated ? <span className="m-badge m-badge--green">已生成</span> : <span className="m-badge">生成中</span>}
        </div>
        {generated ? (
          <div className={`m-art m-art--${style} m-art--${ratio.replace(':', '-')}`.trim()}>
            <div className="m-art__sky" />
            <div className="m-art__sun" />
            <div className="m-art__ridge m-art__ridge--far" />
            <div className="m-art__ridge m-art__ridge--near" />
            <div className="m-art__water" />
            <div className="m-art__grain" />
            <div className="m-art__meta">
              <span>{STYLES.find((item) => item.id === style)?.label}</span>
              <span>{ratio}</span>
            </div>
            <div className="m-art__title">封冻之前</div>
          </div>
        ) : (
          <div className="m-skeleton" style={{ aspectRatio: '3 / 4', borderRadius: 16 }} />
        )}
        <div className="m-detail-actions" style={{ marginTop: 12 }}>
          <button className="m-btn m-btn--sm" type="button" onClick={generate} disabled={busy}>
            <IconRefresh size={15} />
            换一张
          </button>
          <button className="m-btn m-btn--sm m-btn--soft" type="button" onClick={() => setSheet(true)}>
            <IconSparkles size={15} />
            用作章节插图
          </button>
        </div>
      </section>

      <div className="m-card m-card--pad" style={{ marginTop: 20 }}>
        <p className="m-eyebrow">BOUNDARY</p>
        <p className="m-body" style={{ marginTop: 6 }}>
          插图只作辅助表达，不替代正文；作者对画面与文字的最终组合负责。真实生成会记录模型、提示词与来源，随稿可审计。
        </p>
      </div>

      <Sheet open={sheet} onClose={() => setSheet(false)} title="使用这张概念图">
        <div className="m-sheet__row">
          <span className="m-sheet__label">用途</span>
          <span className="m-sheet__value">章节插图 / 书封草案</span>
        </div>
        <div className="m-sheet__row">
          <span className="m-sheet__label">来源</span>
          <span className="m-sheet__value">本地 CSS 概念图</span>
        </div>
        <div className="m-sheet__row">
          <span className="m-sheet__label">版权</span>
          <span className="m-sheet__value">作者保留，平台仅提供工具</span>
        </div>
        <button
          className="m-btn m-btn--primary m-btn--block"
          type="button"
          onClick={() => {
            setSheet(false);
            onToast('已存入《晚潮记》第 13 章素材');
          }}
          style={{ marginTop: 14 }}
        >
          <IconDownload size={16} />
          存入当前章节
        </button>
      </Sheet>
    </div>
  );
}
