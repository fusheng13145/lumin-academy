import { MOBILE_BOOKS } from './library';
import type { MemoryFact, StudioTask, StudioWork } from '../types';

const b1 = MOBILE_BOOKS.find((book) => book.id === 'b1');
const b2 = MOBILE_BOOKS.find((book) => book.id === 'b2');

if (!b1 || !b2) {
  throw new Error('作者工作室缺少演示书目');
}

export const STUDIO_WORKS: StudioWork[] = [
  {
    id: 'w1',
    bookId: b1.id,
    title: b1.title,
    subtitle: '连载中 · 纪事文学',
    status: 'serial',
    statusLabel: '连载中',
    chapterCount: 12,
    words: '41.1 万',
    reads: '128.4 万',
    favorites: '3.2 万',
    revenue: '¥ 4,286',
    updatedAt: '今天 09:12',
    cover: b1.cover,
  },
  {
    id: 'w2',
    bookId: b2.id,
    title: '雾港手记',
    subtitle: '草稿 · 新作筹备',
    status: 'draft',
    statusLabel: '草稿',
    chapterCount: 3,
    words: '1.8 万',
    reads: '—',
    favorites: '—',
    revenue: '—',
    updatedAt: '昨天 22:40',
    cover: ['#E7E2D4', '#2E302B'],
  },
];

export const STUDIO_TASKS: StudioTask[] = [
  {
    id: 't1',
    title: '《晚潮记》第 13 章待更新',
    meta: '读者追更 2,416 · 建议今天 20:00 前发布',
    tone: 'accent',
    actionLabel: '继续写',
    target: 'editor',
  },
  {
    id: 't2',
    title: 'AI 草稿待确认',
    meta: '纯 AI 工作流已生成 1 个候选 · 等待作者审阅',
    tone: 'green',
    actionLabel: '去确认',
    target: 'ai',
  },
  {
    id: 't3',
    title: '12 条读者评论待回复',
    meta: '含 2 条长评 · 最近一条在 18 分钟前',
    tone: 'neutral',
    actionLabel: '查看',
    target: 'comments',
  },
];

export const STUDIO_METRICS = [
  { label: '累计阅读', value: '128.4', unit: '万', delta: '+12.6%' },
  { label: '收藏', value: '3.2', unit: '万', delta: '+842' },
  { label: '本月收益', value: '4,286', unit: '元', delta: '+18.4%' },
];

export const DEFAULT_MEMORY: MemoryFact[] = [
  {
    id: 'm1',
    kind: '世界',
    label: '潮汐城 · 地理',
    value: '沿海小城，三座停用灯塔，冬季有封冻期。',
    locked: true,
  },
  {
    id: 'm2',
    kind: '人物',
    label: '林砚 · 主角',
    value: '潮位观测员，习惯把情绪写进值班簿的边角。',
    locked: true,
  },
  {
    id: 'm3',
    kind: '情节',
    label: '被涂掉的记录',
    value: '旧值班簿中一行被墨迹覆盖的救援记录。',
    locked: true,
  },
  {
    id: 'm4',
    kind: '伏笔',
    label: '第三盏灯',
    value: '堤上第三盏灯从未点亮，与父亲有关。',
    locked: false,
  },
];

export const DEFAULT_DRAFT = {
  title: '第 13 章 · 封冻之前',
  body: `封冻前最后一班船靠岸时，码头上的灯还没有亮。

林砚把新抄好的潮位表夹进值班簿，翻到最后一页，才发现纸背有一道很浅的铅笔痕。那不是她写的字，也不是前任观测员的笔迹。她拿指尖沿着那道痕往下走，走到一半，窗外传来缆绳收紧的声响。

她抬头看了一眼钟。比预报的靠岸时间早了十一分钟。

这个数字被她记进本子，又在旁边添了一个问号。过去三年里，这艘船从不早到。`,
};
