import type { CSSProperties } from 'react';

// 内联 SVG 图标集（扁平、圆润，仿番茄小说）
// 不引入任何外部图标库 / 字体图标，全部内联 path。
export type IconName =
  | 'catalog'
  | 'author'
  | 'review'
  | 'report'
  | 'config'
  | 'mine'
  | 'settings'
  | 'moon'
  | 'sun'
  | 'text'
  | 'brightness'
  | 'note'
  | 'chevron'
  | 'check'
  | 'clock'
  | 'bell';

const PATHS: Record<IconName, string> = {
  // 书城 / 目录：翻开的书
  catalog:
    'M4 5.5C4 4.7 4.7 4 5.5 4H11v14H5.5C4.7 18 4 17.3 4 16.5v-11Zm16 0c0-.8-.7-1.5-1.5-1.5H13v14h6.5c.8 0 1.5-.7 1.5-1.5v-11Z',
  // 书架图标已按 K-2 裁决移除（不设书架入口）
  // 创作：钢笔尖
  author:
    'M14.5 3.5 20 9l-2 2-5.5-5.5 2-2ZM4 20l3.5-1 8-8-2.5-2.5-8 8L4 20Z',
  // 审核：盾 + 勾
  review:
    'M12 3 5 6v5c0 4 3 7 7 8 4-1 7-4 7-8V6l-7-3Zm-2 9 5-5-1.4-1.4L10 9.2 8.4 7.6 7 9l3 3Z',
  // 举报：旗
  report:
    'M5 3v18h2v-7h9l-1.5-2L16 10H7V5h9V3H5Z',
  // 配置：齿轮
  config:
    'M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7Zm9 3.5-2-.7.3-1.9-1.8-1-1.4 1.3-1.8-.4-.7-1.8h-2.4l-.7 1.8-1.8.4-1.4-1.3-1.8 1 .3 1.9-2 .7v2.4l2 .7-.3 1.9 1.8 1 1.4-1.3 1.8.4.7 1.8h2.4l.7-1.8 1.8-.4 1.4 1.3 1.8-1-.3-1.9 2-.7V12Z',
  // 我的：人
  mine:
    'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm0 2c-4 0-8 2-8 5v1h16v-1c0-3-4-5-8-5Z',
  // 搜索图标已按 K-1 裁决移除（不设搜索框）
  // 设置
  settings:
    'M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7Zm9 3.5-2-.7.3-1.9-1.8-1-1.4 1.3-1.8-.4-.7-1.8h-2.4l-.7 1.8-1.8.4-1.4-1.3-1.8 1 .3 1.9-2 .7v2.4l2 .7-.3 1.9 1.8 1 1.4-1.3 1.8.4.7 1.8h2.4l.7-1.8 1.8-.4 1.4 1.3 1.8-1-.3-1.9 2-.7V12Z',
  // 月亮（夜间）
  moon: 'M20 14.5A8 8 0 0 1 9.5 4 7 7 0 1 0 20 14.5Z',
  // 太阳
  sun: 'M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10Zm0-5v3m0 14v3M3 12h3m14 0h3M5.6 5.6l2.1 2.1m8.6 8.6 2.1 2.1m0-12.8-2.1 2.1M7.7 16.3l-2.1 2.1',
  // 字号 Aa
  text: 'M5 18 10 6l5 12h-2l-1.1-2.6H8.1L7 18H5Zm3.4-4.4h3.2L10 9.2 8.4 13.6ZM18 18v-8h-1.6v-1.6H21V10H19.4v8H18Z',
  // 亮度
  brightness:
    'M12 4a1 1 0 0 1 1 1v2a1 1 0 1 1-2 0V5a1 1 0 0 1 1-1Zm0 12a1 1 0 0 1 1 1v2a1 1 0 1 1-2 0v-2a1 1 0 0 1 1-1ZM4 12a1 1 0 0 1 1-1h2a1 1 0 1 1 0 2H5a1 1 0 0 1-1-1Zm14 0a1 1 0 0 1 1-1h2a1 1 0 1 1 0 2h-2a1 1 0 0 1-1-1ZM6.3 6.3a1 1 0 0 1 1.4 0l1.4 1.4A1 1 0 0 1 7.7 9.1L6.3 7.7a1 1 0 0 1 0-1.4Zm9.6 9.6a1 1 0 0 1 1.4 0l1.4 1.4a1 1 0 0 1-1.4 1.4l-1.4-1.4a1 1 0 0 1 0-1.4ZM16.3 6.3a1 1 0 0 1 0 1.4L14.9 9.1a1 1 0 0 1-1.4-1.4l1.4-1.4a1 1 0 0 1 1.4 0ZM6.9 15.9a1 1 0 0 1 0 1.4L5.5 18.7a1 1 0 0 1-1.4-1.4l1.4-1.4a1 1 0 0 1 1.4 0Z',
  // 边注
  note: 'M5 4h14v11H9l-4 4V4Zm2 3v2h10V7H7Zm0 4v2h6v-2H7Z',
  // 箭头
  chevron: 'M9 6l6 6-6 6',
  // 勾
  check: 'M5 13l4 4L19 7',
  // 时钟
  clock: 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16Zm0 2a6 6 0 1 1 0 12 6 6 0 0 1 0-12Zm-1 2v5l4 2 .8-1.6L12 11.4V8h-1Z',
  // 铃
  bell: 'M12 3a5 5 0 0 0-5 5v3l-2 3v1h14v-1l-2-3V8a5 5 0 0 0-5-5Zm0 16a2.5 2.5 0 0 0 2.4-2H9.6A2.5 2.5 0 0 0 12 19Z',
};

export interface IconProps {
  name: IconName;
  size?: number;
  className?: string;
  style?: CSSProperties;
}

export function Icon({ name, size = 22, className, style }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      style={style}
      aria-hidden="true"
      focusable="false"
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
